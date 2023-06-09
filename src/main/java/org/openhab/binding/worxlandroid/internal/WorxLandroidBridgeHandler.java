/**
 * Copyright (c) 2010-2023 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.worxlandroid.internal;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.Map;
import java.util.Optional;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.openhab.binding.worxlandroid.internal.config.BridgeConfiguration;
import org.openhab.binding.worxlandroid.internal.config.ConfigurationLevel;
import org.openhab.binding.worxlandroid.internal.discovery.MowerDiscoveryService;
import org.openhab.binding.worxlandroid.internal.mqtt.AWSClient;
import org.openhab.binding.worxlandroid.internal.mqtt.AWSClientCallback;
import org.openhab.binding.worxlandroid.internal.mqtt.AWSClientI;
import org.openhab.binding.worxlandroid.internal.mqtt.AWSException;
import org.openhab.binding.worxlandroid.internal.mqtt.AWSMessageI;
import org.openhab.binding.worxlandroid.internal.mqtt.AWSTopicI;
import org.openhab.binding.worxlandroid.internal.webapi.WebApiException;
import org.openhab.binding.worxlandroid.internal.webapi.WorxLandroidWebApiImpl;
import org.openhab.binding.worxlandroid.internal.webapi.response.ProductItemsStatusResponse;
import org.openhab.core.auth.client.oauth2.AccessTokenRefreshListener;
import org.openhab.core.auth.client.oauth2.AccessTokenResponse;
import org.openhab.core.auth.client.oauth2.OAuthClientService;
import org.openhab.core.auth.client.oauth2.OAuthException;
import org.openhab.core.auth.client.oauth2.OAuthFactory;
import org.openhab.core.auth.client.oauth2.OAuthResponseException;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseBridgeHandler;
import org.openhab.core.types.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link WorxLandroidBridgeHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author Nils - Initial contribution
 * @author Gaël L'hopital - Refactored with oAuthFactory
 */
@NonNullByDefault
public class WorxLandroidBridgeHandler extends BaseBridgeHandler
        implements AWSClientCallback, AccessTokenRefreshListener {
    private static final String APIURL_OAUTH_TOKEN = "https://id.eu.worx.com/" + "oauth/token";
    private static final String CLIENT_ID = "013132A8-DB34-4101-B993-3C8348EA0EBC";

    private final Logger logger = LoggerFactory.getLogger(WorxLandroidBridgeHandler.class);

    private final OAuthFactory oAuthFactory;
    private final HttpClient httpClient;

    private Optional<MowerDiscoveryService> discoveryService = Optional.empty();
    private @Nullable OAuthClientService oAuthClientService;
    private @Nullable AWSClientI awsClient;
    private @Nullable WorxLandroidWebApiImpl apiHandler;

    public WorxLandroidBridgeHandler(Bridge bridge, HttpClient httpClient, OAuthFactory oAuthFactory) {
        super(bridge);
        this.oAuthFactory = oAuthFactory;
        this.httpClient = httpClient;
    }

    public void setDiscovery(MowerDiscoveryService discoveryService) {
        this.discoveryService = Optional.of(discoveryService);
    }

    @Override
    public void initialize() {
        logger.debug("Initializing Landroid API bridge handler.");

        BridgeConfiguration configuration = getConfigAs(BridgeConfiguration.class);

        if (configuration.webapiUsername.isBlank()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    ConfigurationLevel.EMPTY_USERNAME.message);
            return;
        }

        if (configuration.webapiPassword.isBlank()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    ConfigurationLevel.EMPTY_PASSWORD.message);
            return;
        }

        updateStatus(ThingStatus.UNKNOWN);

        OAuthClientService clientService = oAuthFactory.createOAuthClientService(getThing().getUID().getAsString(),
                APIURL_OAUTH_TOKEN, null, CLIENT_ID, null, "*", true);
        oAuthClientService = clientService;

        try {
            AccessTokenResponse token = clientService.getAccessTokenByResourceOwnerPasswordCredentials(
                    configuration.webapiUsername, configuration.webapiPassword, "*");
            clientService.addAccessTokenRefreshListener(this);
            WorxLandroidWebApiImpl api = new WorxLandroidWebApiImpl(httpClient, clientService);

            Map<String, String> props = api.retrieveWebInfo().getDataAsPropertyMap();

            String userId = props.get("id");
            if (userId == null) {
                throw new WebApiException("No user id found");
            }

            updateThing(editThing().withProperties(props).build());

            ProductItemsStatusResponse productItemsStatusResponse = api.retrieveDeviceStatus(userId);
            Map<String, String> firstDeviceProps = productItemsStatusResponse.getArrayDataAsPropertyMap();

            String awsMqttEndpoint = firstDeviceProps.get("mqtt_endpoint");
            String deviceId = firstDeviceProps.get("uuid");
            String customAuthorizerName = "com-worxlandroid-customer";
            String usernameMqtt = "openhab";
            String clientId = "WX/USER/%s/%s/%s".formatted(userId, usernameMqtt, deviceId);

            AWSClient localAwsClient = new AWSClient(awsMqttEndpoint, clientId, this, usernameMqtt,
                    customAuthorizerName, token.getAccessToken());

            logger.debug("try to connect to AWS...");
            boolean awsConnected = localAwsClient.connect();

            logger.debug("AWS connected: {}", awsConnected);

            awsClient = localAwsClient;
            apiHandler = api;

            // Trigger discovery of mowers
            scheduler.submit(() -> discoveryService.ifPresent(MowerDiscoveryService::discoverMowers));
        } catch (OAuthException | IOException | WebApiException e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
        } catch (OAuthResponseException e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "@text/oauth-connection-error");
        }
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        logger.debug("Landroid Bridge is read-only and does not handle commands");
    }

    @Override
    public void dispose() {
        AWSClientI aws = awsClient;
        if (aws != null) {
            aws.disconnect();
        }
        OAuthClientService clientService = oAuthClientService;
        if (clientService != null) {
            clientService.removeAccessTokenRefreshListener(this);
        }
        super.dispose();
    }

    public @Nullable WorxLandroidWebApiImpl getWorxLandroidWebApiImpl() {
        return apiHandler;
    }

    public boolean isBridgeOnline() {
        // TODO NB
        return getThing().getStatus() == ThingStatus.ONLINE;
    }

    public boolean reconnectToWorx() {
        AWSClientI localAwsClient = awsClient;
        WorxLandroidWebApiImpl api = apiHandler;
        if (localAwsClient != null && api != null) {
            try {
                String accessToken = api.getAccessToken();
                logger.debug("try to reconnect to AWS...");
                boolean connected = localAwsClient.refreshConnection(accessToken);
                logger.debug("AWS reconnected: {}", connected);
                return connected;
            } catch (WebApiException | UnsupportedEncodingException e) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "Error: " + e.getMessage());
            }
        }
        return false;
    }

    /**
     * @param awsTopic
     * @throws AWSIotException
     */
    public void subcribeTopic(@Nullable AWSTopicI awsTopic) throws AWSException {
        if (awsTopic == null) {
            return;
        }
        AWSClientI localAwsClient = awsClient;
        if (localAwsClient == null) {
            logger.error("MqttClient is not initialized. Cannot subsribe to topic -> {}", awsTopic.getTopic());
            return;
        }
        logger.debug("subsribe to topic -> {}", awsTopic.getTopic());
        localAwsClient.subscribe(awsTopic);
    }

    /**
     * @param awsMessage
     * @throws AWSIotException
     */
    @SuppressWarnings("null")
    public void publishMessage(AWSMessageI awsMessage) throws AWSException {
        if (awsClient == null) {
            logger.error("MqttClient is not initialized. Cannot publish message to topic -> {}", awsMessage.getTopic());
            return;
        }

        logger.debug("publish topic -> {}", awsMessage.getTopic());
        logger.debug("publish message -> {}", awsMessage.getPayload());
        awsClient.publish(awsMessage);
    }

    @Override
    public void onAWSConnectionSuccess() {
        logger.debug("AWS connection is available");
        if (!isBridgeOnline()) {
            updateStatus(ThingStatus.ONLINE);
        }
    }

    @Override
    public void onAWSConnectionClosed() {
        logger.debug("AWS connection closed -> reconnectToWorx");
        boolean reconnected = reconnectToWorx();

        // TODO NB stauts prüfen um log nachrichten zu vermeiden???
        if (!reconnected && getThing().getStatus() != ThingStatus.OFFLINE) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "AWS connection closed!");
        }
    }

    @Override
    public void onAccessTokenResponse(AccessTokenResponse tokenResponse) {
        if (isBridgeOnline()) {
            logger.debug("refreshConnectionToken -> reconnectToWorx");
            reconnectToWorx();
        }
    }
}
