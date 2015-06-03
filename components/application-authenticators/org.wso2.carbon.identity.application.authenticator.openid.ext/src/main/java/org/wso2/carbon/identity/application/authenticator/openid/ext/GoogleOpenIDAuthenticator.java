/*
 * Copyright (c) 2014, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.wso2.carbon.identity.application.authenticator.openid.ext;

import org.wso2.carbon.identity.application.authentication.framework.context.AuthenticationContext;
import org.wso2.carbon.identity.application.authentication.framework.exception.AuthenticationFailedException;
import org.wso2.carbon.identity.application.authenticator.openid.OpenIDAuthenticator;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class GoogleOpenIDAuthenticator extends OpenIDAuthenticator {

    /**
     *
     */
    private static final long serialVersionUID = -5947608036809026467L;

    @Override
    public String getFriendlyName() {
        return "google";
    }

    @Override
    public String getName() {
        return "GoogleOpenIDAuthenticator";
    }
    @Override
    protected String getOpenIDServerUrl() {
        return "https://www.google.com/accounts/o8/id";
    }

    @Override
    protected void processAuthenticationResponse(HttpServletRequest request,
                                                 HttpServletResponse response, AuthenticationContext context)
            throws AuthenticationFailedException {
        super.processAuthenticationResponse(request, response, context);

        String subject = super.getSubjectFromUserIDClaimURI(context);
        if (subject != null) {
            context.getSubject().setAuthenticatedSubjectIdentifier(subject);
        }
    }

}
