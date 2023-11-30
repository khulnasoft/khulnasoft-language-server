/*
 * SonarLint Language Server
 * Copyright (C) 2009-2023 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonarsource.sonarlint.ls.backend;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import javax.annotation.Nullable;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.sonarsource.sonarlint.core.http.HttpClient;
import org.sonarsource.sonarlint.core.rpc.client.ClientJsonRpcLauncher;
import org.sonarsource.sonarlint.core.rpc.client.SonarLintRpcClientDelegate;
import org.sonarsource.sonarlint.core.rpc.impl.BackendJsonRpcLauncher;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.config.binding.BindingConfigurationDto;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.config.scope.ConfigurationScopeDto;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.config.scope.DidAddConfigurationScopesParams;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.connection.auth.HelpGenerateUserTokenParams;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.connection.auth.HelpGenerateUserTokenResponse;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.connection.common.TransientSonarCloudConnectionDto;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.connection.common.TransientSonarQubeConnectionDto;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.connection.projects.GetAllProjectsParams;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.connection.projects.GetAllProjectsResponse;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.connection.validate.ValidateConnectionParams;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.connection.validate.ValidateConnectionResponse;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.file.GetPathTranslationParams;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.file.GetPathTranslationResponse;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.hotspot.CheckLocalDetectionSupportedParams;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.hotspot.CheckLocalDetectionSupportedResponse;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.initialize.ClientConstantInfoDto;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.initialize.FeatureFlagsDto;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.initialize.InitializeParams;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.issue.CheckStatusChangePermittedParams;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.issue.CheckStatusChangePermittedResponse;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.issue.ReopenAllIssuesForFileParams;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.issue.ReopenAllIssuesForFileResponse;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.rules.GetEffectiveRuleDetailsParams;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.rules.GetEffectiveRuleDetailsResponse;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.rules.GetStandaloneRuleDescriptionParams;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.rules.GetStandaloneRuleDescriptionResponse;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.rules.ListAllStandaloneRulesDefinitionsResponse;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.rules.StandaloneRuleConfigDto;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.rules.UpdateStandaloneRulesConfigurationParams;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.telemetry.GetStatusResponse;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.telemetry.TelemetryRpcService;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.tracking.TrackWithServerIssuesParams;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.tracking.TrackWithServerIssuesResponse;
import org.sonarsource.sonarlint.core.rpc.protocol.client.telemetry.TelemetryConstantAttributesDto;
import org.sonarsource.sonarlint.ls.connected.ProjectBindingWrapper;
import org.sonarsource.sonarlint.ls.settings.ServerConnectionSettings;
import org.sonarsource.sonarlint.ls.settings.SettingsManager;
import org.sonarsource.sonarlint.ls.telemetry.SonarLintTelemetry;
import org.sonarsource.sonarlint.ls.telemetry.TelemetryInitParams;

public class BackendServiceFacade {

  public static final String ROOT_CONFIGURATION_SCOPE = "<root>";

  private final BackendService backendService;
  private final BackendInitParams initParams;
  private final ConfigurationScopeDto rootConfigurationScope;
  private final BackendJsonRpcLauncher serverLauncher;
  private final ClientJsonRpcLauncher clientLauncher;
  private SettingsManager settingsManager;
  private SonarLintTelemetry telemetry;
  private TelemetryInitParams telemetryInitParams;
  private final AtomicBoolean initialized = new AtomicBoolean(false);

  public BackendServiceFacade(SonarLintRpcClientDelegate rpcClient,  LanguageClientLogger lsLogOutput, SonarLintExtendedLanguageClient client) {
    var clientToServerOutputStream = new PipedOutputStream();
    PipedInputStream clientToServerInputStream = null;
    try {
      clientToServerInputStream = new PipedInputStream(clientToServerOutputStream);
      var serverToClientOutputStream = new PipedOutputStream();
      var serverToClientInputStream = new PipedInputStream(serverToClientOutputStream);
      serverLauncher = new BackendJsonRpcLauncher(clientToServerInputStream, serverToClientOutputStream);
      clientLauncher = new ClientJsonRpcLauncher(serverToClientInputStream, clientToServerOutputStream, rpcClient);
      this.backendService = new BackendService(clientLauncher.getServerProxy(), lsLogOutput, client);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    this.initParams = new BackendInitParams();
    this.rootConfigurationScope = new ConfigurationScopeDto(ROOT_CONFIGURATION_SCOPE, null, false, ROOT_CONFIGURATION_SCOPE,
      new BindingConfigurationDto(null, null, false)
    );
  }

  public BackendService getBackendService() {
    if (!initialized.get()) {
      throw new IllegalStateException("Backend service is not initialized");
    }
    return backendService;
  }

  public void setSettingsManager(SettingsManager settingsManager) {
    this.settingsManager = settingsManager;
  }

  public BackendInitParams getInitParams() {
    return initParams;
  }

  private void initOnce(Map<String, ServerConnectionSettings> connections) {
    if (initialized.getAndSet(true)) return;
    var sqConnections = BackendService.extractSonarQubeConnections(connections);
    var scConnections = BackendService.extractSonarCloudConnections(connections);
    initParams.setSonarQubeConnections(sqConnections);
    initParams.setSonarCloudConnections(scConnections);
    initParams.setStandaloneRuleConfigByKey(settingsManager.getStandaloneRuleConfigByKey());
    initParams.setFocusOnNewCode(settingsManager.getCurrentSettings().isFocusOnNewCode());
    backendService.initialize(toInitParams(initParams));
    backendService.addConfigurationScopes(new DidAddConfigurationScopesParams(List.of(rootConfigurationScope)));
  }

  private InitializeParams toInitParams(BackendInitParams initParams) {
    return new InitializeParams(
      new ClientConstantInfoDto("Visual Studio Code", initParams.getUserAgent()),
      new TelemetryConstantAttributesDto(initParams.getTelemetryProductKey(),
        telemetryInitParams.getProductName(),
        telemetryInitParams.getProductVersion(),
        telemetryInitParams.getIdeVersion(),
        telemetryInitParams.getPlatform(),
        telemetryInitParams.getArchitecture(),
        telemetryInitParams.getAdditionalAttributes()),
      new FeatureFlagsDto(true, true, true,
        true, initParams.isEnableSecurityHotspots(), true, true),
      initParams.getStorageRoot(),
      null,
      initParams.getEmbeddedPluginPaths(),
      initParams.getConnectedModeEmbeddedPluginPathsByKey(),
      initParams.getEnabledLanguagesInStandaloneMode(),
      initParams.getExtraEnabledLanguagesInConnectedMode(),
      initParams.getSonarQubeConnections(),
      initParams.getSonarCloudConnections(),
      initParams.getSonarlintUserHome(),
      initParams.getStandaloneRuleConfigByKey(),
      initParams.isFocusOnNewCode()
    );
  }

  public void shutdown() {
    backendService.shutdown();
    if (backendService != null) {
      try {
        backendService.shutdown().get(10, TimeUnit.SECONDS);
      } catch (ExecutionException | TimeoutException e) {
        // TODO adapt logs
        // Platform.getLog(SonarLintBackendService.class).error("Unable to shutdown the SonartLint backend", e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } finally {
        try {
          serverLauncher.close();
        } catch (Exception e) {
          // Platform.getLog(SonarLintBackendService.class).error("Unable to stop the SonartLint server launcher", e);
        }
        try {
          clientLauncher.close();
        } catch (Exception e) {
          // Platform.getLog(SonarLintBackendService.class).error("Unable to stop the SonartLint client launcher", e);
        }
      }
    }
  }

  public void initialize(Map<String, ServerConnectionSettings> serverConnections) {
    initOnce(serverConnections);
  }

  public void setTelemetry(SonarLintTelemetry telemetry) {
    this.telemetry = telemetry;
  }

  public void setTelemetryInitParams(TelemetryInitParams telemetryInitParams) {
    this.telemetryInitParams = telemetryInitParams;
  }

  public CompletableFuture<GetStatusResponse> getTelemetryStatus() {
    return backendService.getTelemetryStatus();
  }

  public void enableTelemetry() {
    backendService.enableTelemetry();
  }

  public void disableTelemetry() {
    backendService.disableTelemetry();
  }

  public TelemetryRpcService getTelemetryService() {
    return backendService.getTelemetryService();
  }

  public TelemetryInitParams getTelemetryInitParams() {
    return telemetryInitParams;
  }

  public  CompletableFuture<GetPathTranslationResponse> getPathWithTranslation(String folderUri) {
    return backendService.getPathWithTranslation(folderUri);
  }

  public CompletableFuture<GetAllProjectsResponse> getAllProjects(Either<TransientSonarQubeConnectionDto, TransientSonarCloudConnectionDto> transientConnection) {
    return backendService.getAllProjects(transientConnection);
  }

}
