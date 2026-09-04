/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.fineract.evoke.api;

import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import java.lang.management.ManagementFactory;

import com.sun.management.OperatingSystemMXBean;

@Path("/evoke")
@Consumes({MediaType.APPLICATION_JSON})
@Produces({MediaType.APPLICATION_JSON})
@Component
@Scope("singleton")
public class EvokeApiResource {

    @GET
    @Path("/health")
    public String health(@QueryParam("check-cpu") final Boolean checkCpu) {

        if (Boolean.TRUE.equals(checkCpu)) {
            OperatingSystemMXBean osBean =
                    (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

            double cpuLoad = osBean.getSystemCpuLoad();

            if (cpuLoad * 100 > 90.0) {
                throw new RuntimeException(
                        String.format(
                                "Instance CPU usage is too high: %.2f%%",
                                cpuLoad * 100
                        )
                );
            }
        }

        return "OK";
    }
}