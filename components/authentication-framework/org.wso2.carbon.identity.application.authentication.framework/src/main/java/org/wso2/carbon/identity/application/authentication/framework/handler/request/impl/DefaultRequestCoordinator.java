/*
 * Copyright (c) 2013, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.identity.application.authentication.framework.handler.request.impl;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.base.MultitenantConstants;
import org.wso2.carbon.identity.application.authentication.framework.cache.AuthenticationRequestCache;
import org.wso2.carbon.identity.application.authentication.framework.cache.AuthenticationRequestCacheEntry;
import org.wso2.carbon.identity.application.authentication.framework.cache.AuthenticationRequestCacheKey;
import org.wso2.carbon.identity.application.authentication.framework.config.ConfigurationFacade;
import org.wso2.carbon.identity.application.authentication.framework.config.model.SequenceConfig;
import org.wso2.carbon.identity.application.authentication.framework.context.AuthenticationContext;
import org.wso2.carbon.identity.application.authentication.framework.context.SessionContext;
import org.wso2.carbon.identity.application.authentication.framework.exception.FrameworkException;
import org.wso2.carbon.identity.application.authentication.framework.handler.request.RequestCoordinator;
import org.wso2.carbon.identity.application.authentication.framework.internal.FrameworkServiceComponent;
import org.wso2.carbon.identity.application.authentication.framework.model.AuthenticatedUser;
import org.wso2.carbon.identity.application.authentication.framework.util.FrameworkConstants;
import org.wso2.carbon.identity.application.authentication.framework.util.FrameworkUtils;
import org.wso2.carbon.registry.core.utils.UUIDGenerator;
import org.wso2.carbon.user.api.Tenant;

import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;

/**
 * Request Coordinator
 */
public class DefaultRequestCoordinator implements RequestCoordinator {

    private static final Log log = LogFactory.getLog(DefaultRequestCoordinator.class);
    private static volatile DefaultRequestCoordinator instance;

    public static DefaultRequestCoordinator getInstance() {

        if (instance == null) {
            synchronized (DefaultRequestCoordinator.class) {
                if (instance == null) {
                    instance = new DefaultRequestCoordinator();
                }
            }
        }

        return instance;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response) throws IOException {

        try {
            AuthenticationContext context;
            AuthenticationRequestCacheEntry authRequest = null;
            String sessionDataKey = request.getParameter("sessionDataKey");

            boolean returning = false;
            // Check whether this is the start of the authentication flow.
            // 'type' parameter should be present if so. This parameter contains
            // the request type (e.g. samlsso) set by the calling servlet.
            // TODO: use a different mechanism to determine the flow start.
            if (request.getParameter("type") != null) {
                // Retrieve AuthenticationRequestCache Entry which is stored stored from servlet.
                AuthenticationRequestCacheKey cacheKey = new AuthenticationRequestCacheKey(
                        sessionDataKey);
                authRequest = (AuthenticationRequestCacheEntry) AuthenticationRequestCache
                        .getInstance(0).getValueFromCache(cacheKey);
                log.debug("retrieving authentication request from cache..");

                // if there is a cache entry, wrap the original request with params in cache entry
                if (authRequest != null) {
                    request = FrameworkUtils.getCommonAuthReqWithParams(request, authRequest);
                }
                context = initializeFlow(request, response);
            } else {
                returning = true;
                context = FrameworkUtils.getContextData(request);
            }

            if (context != null) {
                context.setReturning(returning);

                // if this is the flow start, store the original request in the context
                if (!context.isReturning() && authRequest != null) {
                    context.setAuthenticationRequest(authRequest.getAuthenticationRequest());
                }


                if (!context.isLogoutRequest()) {
                    FrameworkUtils.getAuthenticationRequestHandler().handle(request, response,
                                                                            context);
                } else {
                    FrameworkUtils.getLogoutRequestHandler().handle(request, response, context);
                }
            } else {
                if (log.isDebugEnabled()) {
                    String key = request.getParameter("sessionDataKey");
                    if (key == null) {
                        log.debug("Session data key is null in the request");
                    } else {
                        log.debug("Session data key  :  " + key);
                    }
                }
                log.error("Context does not exist. Probably due to invalidated cache");
                FrameworkUtils.sendToRetryPage(request, response);
            }
        } catch (Throwable e) {
            log.error("Exception in Authentication Framework", e);
            FrameworkUtils.sendToRetryPage(request, response);
        }
    }

    /**
     * Handles the initial request (from the calling servlet)
     *
     * @param request
     * @param response
     * @throws ServletException
     * @throws IOException
     * @throws
     */
    protected AuthenticationContext initializeFlow(HttpServletRequest request,
                                                   HttpServletResponse response) throws FrameworkException {

        if (log.isDebugEnabled()) {
            log.debug("Initializing the flow");
        }


        // "sessionDataKey" - calling servlet maintains its state information
        // using this
        String callerSessionDataKey = request.getParameter(FrameworkConstants.SESSION_DATA_KEY);

        // "commonAuthCallerPath" - path of the calling servlet. This is the url
        // response should be sent to
        String callerPath = getCallerPath(request);

        // "type" - type of the request. e.g. samlsso, openid, oauth, passivests
        String requestType = request.getParameter(FrameworkConstants.RequestParams.TYPE);

        // "relyingParty"
        String relyingParty = request.getParameter(FrameworkConstants.RequestParams.ISSUER);

        // tenant domain
        String tenantDomain = getTenantDomain(request);

        // Store the request data sent by the caller
        AuthenticationContext context = new AuthenticationContext();
        context.setCallerSessionKey(callerSessionDataKey);
        context.setCallerPath(callerPath);
        context.setRequestType(requestType);
        context.setRelyingParty(relyingParty);
        context.setTenantDomain(tenantDomain);

        // generate a new key to hold the context data object
        String contextId = UUIDGenerator.generateUUID();
        context.setContextIdentifier(contextId);

        if (log.isDebugEnabled()) {
            log.debug("Framework contextId: " + contextId);
        }


        // if this a logout request from the calling servlet
        if (request.getParameter(FrameworkConstants.RequestParams.LOGOUT) != null) {

            if (log.isDebugEnabled()) {
                log.debug("Starting a logout flow");
            }

            context.setLogoutRequest(true);

            if (context.getRelyingParty() == null || context.getRelyingParty().trim().length() == 0) {

                if (log.isDebugEnabled()) {
                    log.debug("relyingParty param is null. This is a possible logout scenario.");
                }

                Cookie cookie = FrameworkUtils.getAuthCookie(request);

                if (cookie != null) {
                    context.setSessionIdentifier(cookie.getValue());
                }

                return context;
            }
        } else {
            if (log.isDebugEnabled()) {
                log.debug("Starting an authentication flow");
            }
        }

        findPreviousAuthenticatedSession(request, context);
        buildOutboundQueryString(request, context);

        return context;
    }

    private String getCallerPath(HttpServletRequest request) throws FrameworkException {
        String callerPath = request.getParameter(FrameworkConstants.RequestParams.CALLER_PATH);
        try {
            if (callerPath != null) {
                callerPath = URLDecoder.decode(callerPath, "UTF-8");
            }
        } catch (UnsupportedEncodingException e) {
            throw new FrameworkException(e.getMessage(), e);
        }
        return callerPath;
    }

    private String getTenantDomain(HttpServletRequest request) throws FrameworkException {
        String tenantDomain = request.getParameter(FrameworkConstants.RequestParams.TENANT_DOMAIN);

        if (tenantDomain == null || tenantDomain.isEmpty() || "null".equals(tenantDomain)) {

            String tenantId = request.getParameter(FrameworkConstants.RequestParams.TENANT_ID);

            if (tenantId != null && !"-1234".equals(tenantId)) {
                try {
                    Tenant tenant = FrameworkServiceComponent.getRealmService().getTenantManager()
                            .getTenant(Integer.parseInt(tenantId));
                    if (tenant != null) {
                        tenantDomain = tenant.getDomain();
                    }
                } catch (Exception e) {
                    throw new FrameworkException(e.getMessage(), e);
                }
            } else {
                tenantDomain = MultitenantConstants.SUPER_TENANT_DOMAIN_NAME;
            }
        }
        return tenantDomain;
    }

    protected void findPreviousAuthenticatedSession(HttpServletRequest request,
                                                    AuthenticationContext context) throws FrameworkException {

        // Get service provider chain
        SequenceConfig sequenceConfig = ConfigurationFacade.getInstance().getSequenceConfig(
                context.getRequestType(),
                request.getParameter(FrameworkConstants.RequestParams.ISSUER),
                context.getTenantDomain());

        Cookie cookie = FrameworkUtils.getAuthCookie(request);

        // if cookie exists user has previously authenticated
        if (cookie != null) {

            if (log.isDebugEnabled()) {
                log.debug(FrameworkConstants.COMMONAUTH_COOKIE
                          + " cookie is available with the value: " + cookie.getValue());
            }

            // get the authentication details from the cache
            SessionContext sessionContext = FrameworkUtils.getSessionContextFromCache(cookie
                                                                                              .getValue());

            if (sessionContext != null) {
                context.setSessionIdentifier(cookie.getValue());
                String appName = sequenceConfig.getApplicationConfig().getApplicationName();

                if (log.isDebugEnabled()) {
                    log.debug("Service Provider is: " + appName);
                }

                SequenceConfig previousAuthenticatedSeq = sessionContext
                        .getAuthenticatedSequences().get(appName);

                if (previousAuthenticatedSeq != null) {

                    if (log.isDebugEnabled()) {
                        log.debug("A previously authenticated sequence found for the SP: "
                                  + appName);
                    }

                    context.setPreviousSessionFound(true);
                    sequenceConfig = previousAuthenticatedSeq;
                    AuthenticatedUser authenticatedUser = sequenceConfig.getAuthenticatedUser();
                    String authenticatedUserTenantDomain = sequenceConfig.getAuthenticatedUserTenantDomain();

                    if (authenticatedUser != null) {
                        // set the user for the current authentication/logout flow
                        context.setSubject(authenticatedUser);

                        if (log.isDebugEnabled()) {
                            log.debug("Already authenticated by username: " +
                                      authenticatedUser.getAuthenticatedSubjectIdentifier());
                        }

                        if (authenticatedUserTenantDomain != null) {
                            // set the user tenant domain for the current authentication/logout flow
                            context.setProperty("user-tenant-domain", authenticatedUserTenantDomain);

                            if (log.isDebugEnabled()) {
                                log.debug("Authenticated user tenant domain: " + authenticatedUserTenantDomain);
                            }
                        }
                    }
                }

                context.setPreviousAuthenticatedIdPs(sessionContext.getAuthenticatedIdPs());
            } else {
                if (log.isDebugEnabled()) {
                    log.debug("Failed to find the SessionContext from the cache. Possible cache timeout.");
                }
            }
        }

        context.setServiceProviderName(sequenceConfig.getApplicationConfig().getApplicationName());

        // set the sequence for the current authentication/logout flow
        context.setSequenceConfig(sequenceConfig);
    }

    private void buildOutboundQueryString(HttpServletRequest request, AuthenticationContext context) {

        // Build the outbound query string that will be sent to the authentication endpoint and
        // federated IdPs
        StringBuilder outboundQueryStringBuilder = new StringBuilder();
        outboundQueryStringBuilder.append(FrameworkUtils.getQueryStringWithConfiguredParams(request));

        if (StringUtils.isNotEmpty(outboundQueryStringBuilder.toString())) {
            outboundQueryStringBuilder.append("&");
        }

        outboundQueryStringBuilder.append("sessionDataKey=").append(context.getContextIdentifier())
                .append("&relyingParty=").append(context.getRelyingParty()).append("&type=")
                .append(context.getRequestType()).append("&sp=")
                .append(context.getServiceProviderName()).append("&isSaaSApp=")
                .append(context.getSequenceConfig().getApplicationConfig().isSaaSApp());

        if (log.isDebugEnabled()) {
            log.debug("Outbound Query String: " + outboundQueryStringBuilder.toString());
        }

        context.setContextIdIncludedQueryParams(outboundQueryStringBuilder.toString());
        context.setOrignalRequestQueryParams(outboundQueryStringBuilder.toString());
    }
}
