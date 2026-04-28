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
package org.apache.fineract.useradministration.api;

import com.google.gson.JsonElement;
import com.google.gson.reflect.TypeToken;
import org.apache.commons.lang.StringUtils;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResultBuilder;
import org.apache.fineract.infrastructure.core.exception.InvalidJsonException;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.serialization.DefaultToApiJsonSerializer;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.security.service.PlatformPasswordEncoder;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.useradministration.domain.AppUser;
import org.apache.fineract.useradministration.domain.AppUserPreviousPassword;
import org.apache.fineract.useradministration.domain.AppUserPreviousPasswordRepository;
import org.apache.fineract.useradministration.domain.AppUserRepository;
import org.apache.fineract.useradministration.exception.PasswordPreviouslyUsedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Path("/usersettings/password")
@Component
public class UserPasswordApiResource {

    private final PlatformSecurityContext context;
    private final FromJsonHelper fromApiJsonHelper;
    private final Set<String> supportedParameters = new HashSet<>(Arrays.asList("password", "repeatPassword"));
    private final AppUserRepository appUserRepository;
    private final PlatformPasswordEncoder platformPasswordEncoder;
    private final AppUserPreviousPasswordRepository appUserPreviewPasswordRepository;
    private final DefaultToApiJsonSerializer<AppUser> toApiJsonSerializer;

    @Autowired
    public UserPasswordApiResource(final PlatformSecurityContext context,
                                   final FromJsonHelper fromApiJsonHelper,
                                   final AppUserRepository appUserRepository,
                                   final PlatformPasswordEncoder platformPasswordEncoder,
                                   final AppUserPreviousPasswordRepository appUserPreviewPasswordRepository,
                                   final DefaultToApiJsonSerializer<AppUser> toApiJsonSerializer) {

        this.context = context;
        this.fromApiJsonHelper = fromApiJsonHelper;
        this.appUserRepository = appUserRepository;
        this.platformPasswordEncoder = platformPasswordEncoder;
        this.appUserPreviewPasswordRepository = appUserPreviewPasswordRepository;
        this.toApiJsonSerializer = toApiJsonSerializer;
    }

    @PUT
    public String updatePassword(final String apiRequestBodyAsJson) {
        if (StringUtils.isBlank(apiRequestBodyAsJson)) {
            throw new InvalidJsonException();
        }

        final Type typeOfMap = new TypeToken<Map<String, Object>>() {
        }.getType();
        this.fromApiJsonHelper.checkForUnsupportedParameters(typeOfMap,
                apiRequestBodyAsJson,
                this.supportedParameters);

        final AppUser appUser = this.context.authenticatedUser();

        final JsonElement parsedCommand = this.fromApiJsonHelper.parse(apiRequestBodyAsJson);
        final String password = this.fromApiJsonHelper.extractStringNamed("password", parsedCommand);
        final String repeatPassword = this.fromApiJsonHelper.extractStringNamed("repeatPassword", parsedCommand);

        final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
        if (password == null || !password.equals(repeatPassword)) {
            final ApiParameterError error = ApiParameterError.parameterError("error.msg.user.password.and.repeat.password.not.match",
                    "Password and Repeat password do not match.", "password", password);
            dataValidationErrors.add(error);
        }
        if (!dataValidationErrors.isEmpty()) {
            throw new PlatformApiDataValidationException("validation.msg.validation.errors.exist", "Validation errors exist.",
                    dataValidationErrors);
        }

        AppUser userToUpdate = this.appUserRepository.findOne(appUser.getId());

        userToUpdate.updatePassword(password);
        final String passWordEncodedValue = this.platformPasswordEncoder.encode(userToUpdate);

        PageRequest pageRequest = new PageRequest(0, AppUserApiConstant.numberOfPreviousPasswords, Sort.Direction.DESC, "removalDate");
        final List<AppUserPreviousPassword> nLastUsedPasswords = this.appUserPreviewPasswordRepository.findByUserId(userToUpdate.getId(), pageRequest);

        for (AppUserPreviousPassword aPreviewPassword : nLastUsedPasswords) {
            if (aPreviewPassword.getPassword().equals(passWordEncodedValue)) {
                throw new PasswordPreviouslyUsedException();
            }
        }

        userToUpdate.updatePassword(passWordEncodedValue);
        this.appUserRepository.saveAndFlush(userToUpdate);

        AppUserPreviousPassword currentPasswordToSaveAsPreview = new AppUserPreviousPassword(userToUpdate);
        this.appUserPreviewPasswordRepository.save(currentPasswordToSaveAsPreview);

        final CommandProcessingResult result = new CommandProcessingResultBuilder()
                .withEntityId(userToUpdate.getId())
                .withOfficeId(userToUpdate.getOffice().getId())
                .build();

        return this.toApiJsonSerializer.serialize(result);
    }
}
