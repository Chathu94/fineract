package org.apache.fineract.infrastructure.jobs.service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.jobs.domain.ScheduledJobDetail;
import org.apache.fineract.infrastructure.jobs.domain.ScheduledJobDetailRepository;
import org.apache.fineract.infrastructure.security.service.BasicAuthTenantDetailsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;

public class JobRunnerCli {

    private static final Logger logger = LoggerFactory.getLogger(JobRunnerCli.class);

    public static void runJobs(ApplicationContext ctx, String jobIdsParam, boolean exitWhenDone) {
        new Thread(() -> {
            try {
                // Wait briefly for all services to stabilize
                Thread.sleep(3000);
                
                BasicAuthTenantDetailsService tenantDetailsService = ctx.getBean(BasicAuthTenantDetailsService.class);
                FineractPlatformTenant tenant = tenantDetailsService.loadTenantById("default", false);
                ThreadLocalContextUtil.setTenant(tenant);

                JobRegisterService jobRegisterService = ctx.getBean(JobRegisterService.class);
                ScheduledJobDetailRepository jobRepo = ctx.getBean(ScheduledJobDetailRepository.class);

                String[] jobIds = jobIdsParam.split(",");
                System.out.println("================================================================================");
                System.out.println(">>> STARTING SEQUENTIAL EXECUTION OF " + jobIds.length + " SCHEDULER JOBS");
                System.out.println("================================================================================");

                for (String rawId : jobIds) {
                    rawId = rawId.trim();
                    if (rawId.isEmpty()) continue;
                    long jobId = Long.parseLong(rawId);

                    ScheduledJobDetail job = jobRepo.findOne(jobId);
                    String jobName = job != null ? job.getJobName() : ("Job#" + jobId);
                    System.out.println("\n--------------------------------------------------------------------------------");
                    System.out.println(">>> Executing Job " + jobId + ": [" + jobName + "]...");
                    long startMs = System.currentTimeMillis();

                    // Make sure tenant context is set for the current thread
                    ThreadLocalContextUtil.setTenant(tenant);
                    
                    // Reset stuck currently_running if needed
                    resetIfStuck(jobId);

                    // Trigger the job
                    jobRegisterService.executeJob(jobId);

                    // Wait for the job to complete (up to 2 hours)
                    boolean finished = waitForJobCompletion(jobId, 7200);
                    long durationSec = (System.currentTimeMillis() - startMs) / 1000;

                    // Query latest status from DB
                    String[] result = getLatestRunResult(jobId);
                    String status = result[0];
                    String errorMsg = result[1];

                    if ("success".equalsIgnoreCase(status)) {
                        System.out.println(">>> SUCCESS: Job " + jobId + " [" + jobName + "] finished in " + durationSec + "s.");
                    } else {
                        System.out.println(">>> FAILED/STATUS [" + status + "]: Job " + jobId + " [" + jobName + "] finished in " + durationSec + "s.");
                        if (errorMsg != null && !errorMsg.isEmpty()) {
                            System.out.println(">>> Error: " + errorMsg);
                        }
                    }
                    System.out.println("--------------------------------------------------------------------------------");
                    
                    // Wait 2 seconds between sequential jobs to allow DB buffers to flush
                    Thread.sleep(2000);
                }

                System.out.println("\n================================================================================");
                System.out.println(">>> ALL REQUESTED JOBS COMPLETED SEQUENTIALLY!");
                System.out.println("================================================================================");

                if (exitWhenDone) {
                    System.out.println("Exiting application as requested (--exit-after-jobs)...");
                    System.exit(0);
                }
            } catch (Exception e) {
                logger.error("Error executing jobs via CLI runner", e);
                e.printStackTrace();
            }
        }, "JobRunnerCli-Thread").start();
    }

    private static Connection getDirectDbConn() throws Exception {
        Class.forName("com.mysql.cj.jdbc.Driver");
        String host = System.getenv("DS_HOST") != null ? System.getenv("DS_HOST") : "vitess.qa.evokelabs.io";
        String port = System.getenv("DS_PORT") != null ? System.getenv("DS_PORT") : "15306";
        String user = System.getenv("DS_USERNAME") != null ? System.getenv("DS_USERNAME") : "minjanar";
        String pass = System.getenv("DS_PASSWORD") != null ? System.getenv("DS_PASSWORD") : "bHvn5MEl4BNi";
        String db = System.getenv("DS_DATABASE") != null ? System.getenv("DS_DATABASE") : "fused_fli";
        String url = "jdbc:mysql://" + host + ":" + port + "/" + db + "?useSSL=false&allowPublicKeyRetrieval=true";
        Connection conn = DriverManager.getConnection(url, user, pass);
        try (Statement s = conn.createStatement()) {
            s.execute("SET workload = OLAP");
        }
        return conn;
    }

    private static void resetIfStuck(long jobId) {
        try (Connection conn = getDirectDbConn(); Statement s = conn.createStatement()) {
            s.executeUpdate("UPDATE job SET currently_running = 0 WHERE id = " + jobId + " AND currently_running = 1");
        } catch (Exception ignored) {}
    }

    private static boolean waitForJobCompletion(long jobId, int maxWaitSeconds) {
        long deadline = System.currentTimeMillis() + (maxWaitSeconds * 1000L);
        // Wait at least 2 seconds before initial check to let job start
        try { Thread.sleep(2000); } catch (InterruptedException ignored) {}

        while (System.currentTimeMillis() < deadline) {
            try (Connection conn = getDirectDbConn(); Statement s = conn.createStatement()) {
                ResultSet rs = s.executeQuery("SELECT currently_running FROM job WHERE id = " + jobId);
                if (rs.next()) {
                    int running = rs.getInt(1);
                    if (running == 0) {
                        return true;
                    }
                }
            } catch (Exception e) {
                // retry
            }
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                break;
            }
        }
        return false;
    }

    private static String[] getLatestRunResult(long jobId) {
        try (Connection conn = getDirectDbConn(); Statement s = conn.createStatement()) {
            ResultSet rs = s.executeQuery(
                "SELECT status, error_message FROM job_run_history WHERE job_id = " + jobId + " ORDER BY id DESC LIMIT 1"
            );
            if (rs.next()) {
                return new String[] { rs.getString(1), rs.getString(2) };
            }
        } catch (Exception e) {
            return new String[] { "UNKNOWN", e.getMessage() };
        }
        return new String[] { "UNKNOWN", null };
    }
}
