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
import org.apache.fineract.useradministration.domain.PasswordValidationPolicy;
import org.apache.fineract.useradministration.domain.PasswordValidationPolicyRepository;
import org.apache.fineract.useradministration.exception.PasswordPreviouslyUsedException;
import org.apache.fineract.infrastructure.core.data.DataValidatorBuilder;
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
    private final Set<String> supportedParameters = new HashSet<>(
            Arrays.asList("password", "repeatPassword", "currentPassword"));
    private final AppUserRepository appUserRepository;
    private final PlatformPasswordEncoder platformPasswordEncoder;
    private final AppUserPreviousPasswordRepository appUserPreviewPasswordRepository;
    private final DefaultToApiJsonSerializer<AppUser> toApiJsonSerializer;
    private final PasswordValidationPolicyRepository passwordValidationPolicyRepository;

    @Autowired
    public UserPasswordApiResource(final PlatformSecurityContext context,
            final FromJsonHelper fromApiJsonHelper,
            final AppUserRepository appUserRepository,
            final PlatformPasswordEncoder platformPasswordEncoder,
            final AppUserPreviousPasswordRepository appUserPreviewPasswordRepository,
            final DefaultToApiJsonSerializer<AppUser> toApiJsonSerializer,
            final PasswordValidationPolicyRepository passwordValidationPolicyRepository) {

        this.context = context;
        this.fromApiJsonHelper = fromApiJsonHelper;
        this.appUserRepository = appUserRepository;
        this.platformPasswordEncoder = platformPasswordEncoder;
        this.appUserPreviewPasswordRepository = appUserPreviewPasswordRepository;
        this.toApiJsonSerializer = toApiJsonSerializer;
        this.passwordValidationPolicyRepository = passwordValidationPolicyRepository;
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
        final String currentPassword = this.fromApiJsonHelper.extractStringNamed("currentPassword", parsedCommand);

        final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
        final DataValidatorBuilder baseDataValidator = new DataValidatorBuilder(dataValidationErrors).resource("user");

        if (StringUtils.isBlank(currentPassword)) {
            final ApiParameterError error = ApiParameterError.parameterError(
                    "validation.msg.user.current.password.cannot.be.blank",
                    "Current password cannot be blank.", "currentPassword");
            dataValidationErrors.add(error);
        }

        if (password == null || !password.equals(repeatPassword)) {
            final ApiParameterError error = ApiParameterError.parameterError(
                    "error.msg.user.password.and.repeat.password.not.match",
                    "Password and Repeat password do not match.", "password", password);
            dataValidationErrors.add(error);
        }

        if (password != null) {
            final PasswordValidationPolicy validationPolicy = this.passwordValidationPolicyRepository
                    .findActivePasswordValidationPolicy();
            final String regex = validationPolicy.getRegex();
            final String description = validationPolicy.getDescription();
            baseDataValidator.reset().parameter("password").value(password).matchesRegularExpression(regex,
                    description);
        }

        AppUser userToUpdate = this.appUserRepository.findOne(appUser.getId());

        if (StringUtils.isNotBlank(currentPassword)) {
            final String currentHashedPassword = userToUpdate.getPassword();
            userToUpdate.updatePassword(currentPassword);
            final String currentPasswordEncoded = this.platformPasswordEncoder.encode(userToUpdate);
            if (!currentPasswordEncoded.equals(currentHashedPassword)) {
                final ApiParameterError error = ApiParameterError.parameterError(
                        "error.msg.user.current.password.invalid",
                        "Invalid current password.", "currentPassword");
                dataValidationErrors.add(error);
            }
        }

        if (!dataValidationErrors.isEmpty()) {
            throw new PlatformApiDataValidationException("validation.msg.validation.errors.exist",
                    "Validation errors exist.",
                    dataValidationErrors);
        }

        userToUpdate.updatePassword(password);
        final String passWordEncodedValue = this.platformPasswordEncoder.encode(userToUpdate);

        PageRequest pageRequest = new PageRequest(0, AppUserApiConstant.numberOfPreviousPasswords, Sort.Direction.DESC,
                "removalDate");
        final List<AppUserPreviousPassword> nLastUsedPasswords = this.appUserPreviewPasswordRepository
                .findByUserId(userToUpdate.getId(), pageRequest);

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
