package org.apache.fineract.evoke.service;

import org.apache.commons.lang.StringUtils;
import org.apache.fineract.infrastructure.core.api.ApiParameterHelper;
import org.apache.fineract.infrastructure.core.boot.JDBCDriverConfig;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenantConnection;
import org.apache.fineract.infrastructure.core.exception.PlatformDataIntegrityException;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.report.annotation.ReportService;
import org.apache.fineract.infrastructure.report.service.ReportingProcessService;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.useradministration.domain.AppUser;
import org.pentaho.reporting.engine.classic.core.ClassicEngineBoot;
import org.pentaho.reporting.engine.classic.core.DefaultReportEnvironment;
import org.pentaho.reporting.engine.classic.core.MasterReport;
import org.pentaho.reporting.engine.classic.core.ReportProcessingException;
import org.pentaho.reporting.engine.classic.core.modules.output.pageable.pdf.PdfReportUtil;
import org.pentaho.reporting.engine.classic.core.modules.output.table.csv.CSVReportUtil;
import org.pentaho.reporting.engine.classic.core.modules.output.table.html.HtmlReportUtil;
import org.pentaho.reporting.engine.classic.core.modules.output.table.xls.ExcelReportUtil;
import org.pentaho.reporting.engine.classic.core.parameters.ParameterDefinitionEntry;
import org.pentaho.reporting.engine.classic.core.parameters.ReportParameterDefinition;
import org.pentaho.reporting.engine.classic.core.util.ReportParameterValues;
import org.pentaho.reporting.libraries.resourceloader.Resource;
import org.pentaho.reporting.libraries.resourceloader.ResourceException;
import org.pentaho.reporting.libraries.resourceloader.ResourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.sql.Date;
import java.util.*;

@Service
@ReportService(type = "Pentaho")
public class PentahoReportingProcessServiceImpl implements ReportingProcessService {
   private static final Logger logger = LoggerFactory.getLogger(PentahoReportingProcessServiceImpl.class);
   public static final String MIFOS_BASE_DIR;
   private final PlatformSecurityContext context;
   private boolean noPentaho = false;
   @Autowired
   private JDBCDriverConfig driverConfig;

   @Autowired
   public PentahoReportingProcessServiceImpl(PlatformSecurityContext context) {
      ClassicEngineBoot.getInstance().start();
      this.noPentaho = false;
      this.context = context;
   }

   public Response processRequest(String reportName, MultivaluedMap<String, String> queryParams) {
      String outputTypeParam = (String)queryParams.getFirst("output-type");
      Map<String, String> reportParams = this.getReportParams(queryParams);
      Locale locale = ApiParameterHelper.extractLocale(queryParams);
      String outputType = "HTML";
      if (StringUtils.isNotBlank(outputTypeParam)) {
         outputType = outputTypeParam;
      }

      if (!outputType.equalsIgnoreCase("HTML") && !outputType.equalsIgnoreCase("PDF") && !outputType.equalsIgnoreCase("XLS") && !outputType.equalsIgnoreCase("XLSX") && !outputType.equalsIgnoreCase("CSV")) {
         throw new PlatformDataIntegrityException("error.msg.invalid.outputType", "No matching Output Type: " + outputType, new Object[0]);
      } else if (this.noPentaho) {
         throw new PlatformDataIntegrityException("error.msg.no.pentaho", "Pentaho is not enabled", "Pentaho is not enabled", new Object[0]);
      } else {
         String reportPath = MIFOS_BASE_DIR + File.separator + "pentahoReports" + File.separator + reportName + ".prpt";
         logger.info("Report path: " + reportPath);
         ResourceManager manager = new ResourceManager();
         manager.registerDefaults();

         try {
            Resource res = manager.createDirectly(reportPath, MasterReport.class);
            MasterReport masterReport = (MasterReport)res.getResource();
            DefaultReportEnvironment reportEnvironment = (DefaultReportEnvironment)masterReport.getReportEnvironment();
            if (locale != null) {
               reportEnvironment.setLocale(locale);
            }

            this.addParametersToReport(masterReport, reportParams);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            if ("PDF".equalsIgnoreCase(outputType)) {
               PdfReportUtil.createPDF(masterReport, baos);
               return Response.ok().entity(baos.toByteArray()).type("application/pdf").build();
            }

            if ("XLS".equalsIgnoreCase(outputType)) {
               ExcelReportUtil.createXLS(masterReport, baos);
               return Response.ok().entity(baos.toByteArray()).type("application/vnd.ms-excel").header("Content-Disposition", "attachment;filename=" + reportName.replaceAll(" ", "") + ".xls").build();
            }

            if ("XLSX".equalsIgnoreCase(outputType)) {
               ExcelReportUtil.createXLSX(masterReport, baos);
               return Response.ok().entity(baos.toByteArray()).type("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet").header("Content-Disposition", "attachment;filename=" + reportName.replaceAll(" ", "") + ".xlsx").build();
            }

            if ("CSV".equalsIgnoreCase(outputType)) {
               CSVReportUtil.createCSV(masterReport, baos, "UTF-8");
               return Response.ok().entity(baos.toByteArray()).type("text/csv").header("Content-Disposition", "attachment;filename=" + reportName.replaceAll(" ", "") + ".csv").build();
            }

            if ("HTML".equalsIgnoreCase(outputType)) {
               HtmlReportUtil.createStreamHTML(masterReport, baos);
               return Response.ok().entity(baos.toByteArray()).type("text/html").build();
            }
         } catch (ResourceException var13) {
            throw new PlatformDataIntegrityException("error.msg.reporting.error", var13.getMessage(), new Object[0]);
         } catch (ReportProcessingException var14) {
            throw new PlatformDataIntegrityException("error.msg.reporting.error", var14.getMessage(), new Object[0]);
         } catch (IOException var15) {
            throw new PlatformDataIntegrityException("error.msg.reporting.error", var15.getMessage(), new Object[0]);
         }

         throw new PlatformDataIntegrityException("error.msg.invalid.outputType", "No matching Output Type: " + outputType, new Object[0]);
      }
   }

   private void addParametersToReport(MasterReport report, Map<String, String> queryParams) {
      AppUser currentUser = this.context.authenticatedUser();

      try {
         ReportParameterValues rptParamValues = report.getParameterValues();
         ReportParameterDefinition paramsDefinition = report.getParameterDefinition();
         ParameterDefinitionEntry[] var6 = paramsDefinition.getParameterDefinitions();
         int var7 = var6.length;

         for(int var8 = 0; var8 < var7; ++var8) {
            ParameterDefinitionEntry paramDefEntry = var6[var8];
            String paramName = paramDefEntry.getName();
            if (!paramName.equals("tenantUrl") && !paramName.equals("userhierarchy") && !paramName.equals("username") && !paramName.equals("password") && !paramName.equals("userid")) {
               logger.info("paramName:" + paramName);
               String pValue = (String)queryParams.get(paramName);
               if (StringUtils.isBlank(pValue)) {
                  throw new PlatformDataIntegrityException("error.msg.reporting.error", "Pentaho Parameter: " + paramName + " - not Provided", new Object[0]);
               }

               Class<?> clazz = paramDefEntry.getValueType();
               logger.info("addParametersToReport(" + paramName + " : " + pValue + " : " + clazz.getCanonicalName() + ")");
               if (clazz.getCanonicalName().equalsIgnoreCase("java.lang.Integer")) {
                  rptParamValues.put(paramName, Integer.parseInt(pValue));
               } else if (clazz.getCanonicalName().equalsIgnoreCase("java.lang.Long")) {
                  rptParamValues.put(paramName, Long.parseLong(pValue));
               } else if (clazz.getCanonicalName().equalsIgnoreCase("java.sql.Date")) {
                  rptParamValues.put(paramName, Date.valueOf(pValue));
               } else {
                  rptParamValues.put(paramName, pValue);
               }
            }
         }

         FineractPlatformTenant tenant = ThreadLocalContextUtil.getTenant();
         FineractPlatformTenantConnection tenantConnection = tenant.getConnection();
         String tenantUrl = this.driverConfig.constructProtocol(tenantConnection.getSchemaServer(), tenantConnection.getSchemaServerPort(), tenantConnection.getSchemaName());
         String userhierarchy = currentUser.getOffice().getHierarchy();
         logger.info("db URL:" + tenantUrl + "      userhierarchy:" + userhierarchy);
         rptParamValues.put("userhierarchy", userhierarchy);
         Long userid = currentUser.getId();
         logger.info("db URL:" + tenantUrl + "      userid:" + userid);
         rptParamValues.put("userid", userid);
         rptParamValues.put("tenantUrl", tenantUrl);
         rptParamValues.put("username", tenantConnection.getSchemaUsername());
         rptParamValues.put("password", tenantConnection.getSchemaPassword());
      } catch (Exception var13) {
         logger.error("error.msg.reporting.error:" + var13.getMessage());
         throw new PlatformDataIntegrityException("error.msg.reporting.error", var13.getMessage(), new Object[0]);
      }
   }

   private Map<String, String> getReportParams(MultivaluedMap<String, String> queryParams) {
      Map<String, String> reportParams = new HashMap();
      Set<String> keys = queryParams.keySet();
      Iterator var6 = keys.iterator();

      while(var6.hasNext()) {
         String k = (String)var6.next();
         if (k.startsWith("R_")) {
            String pKey = k.substring(2);
            String pValue = (String)((List)queryParams.get(k)).get(0);
            reportParams.put(pKey, pValue);
         }
      }

      return reportParams;
   }

   static {
      MIFOS_BASE_DIR = System.getProperty("user.home") + File.separator + ".mifosx";
   }
}
