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
package org.sonarsource.sonarlint.ls.connected.events;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.sonarsource.sonarlint.core.client.api.connected.ConnectedSonarLintEngine;
import org.sonarsource.sonarlint.core.client.api.connected.ProjectBranches;
import org.sonarsource.sonarlint.core.commons.HotspotReviewStatus;
import org.sonarsource.sonarlint.core.commons.IssueSeverity;
import org.sonarsource.sonarlint.core.commons.RuleType;
import org.sonarsource.sonarlint.core.commons.VulnerabilityProbability;
import org.sonarsource.sonarlint.core.serverapi.push.IssueChangedEvent;
import org.sonarsource.sonarlint.core.serverapi.push.SecurityHotspotChangedEvent;
import org.sonarsource.sonarlint.core.serverapi.push.SecurityHotspotClosedEvent;
import org.sonarsource.sonarlint.core.serverapi.push.SecurityHotspotRaisedEvent;
import org.sonarsource.sonarlint.core.serverapi.push.TaintVulnerabilityClosedEvent;
import org.sonarsource.sonarlint.core.serverapi.push.TaintVulnerabilityRaisedEvent;
import org.sonarsource.sonarlint.core.serverconnection.ProjectBinding;
import org.sonarsource.sonarlint.core.serverconnection.issues.ServerTaintIssue;
import org.sonarsource.sonarlint.ls.AnalysisScheduler;
import org.sonarsource.sonarlint.ls.DiagnosticPublisher;
import org.sonarsource.sonarlint.ls.EnginesFactory;
import org.sonarsource.sonarlint.ls.SonarLintExtendedLanguageClient;
import org.sonarsource.sonarlint.ls.backend.BackendServiceFacade;
import org.sonarsource.sonarlint.ls.connected.ProjectBindingManager;
import org.sonarsource.sonarlint.ls.connected.ProjectBindingWrapper;
import org.sonarsource.sonarlint.ls.connected.TaintVulnerabilitiesCache;
import org.sonarsource.sonarlint.ls.connected.domain.TaintIssue;
import org.sonarsource.sonarlint.ls.connected.notifications.TaintVulnerabilityRaisedNotification;
import org.sonarsource.sonarlint.ls.folders.WorkspaceFolderWrapper;
import org.sonarsource.sonarlint.ls.folders.WorkspaceFoldersManager;
import org.sonarsource.sonarlint.ls.notebooks.OpenNotebooksCache;
import org.sonarsource.sonarlint.ls.settings.ServerConnectionSettings;
import org.sonarsource.sonarlint.ls.settings.SettingsManager;
import org.sonarsource.sonarlint.ls.settings.WorkspaceSettings;
import testutils.SonarLintLogTester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.sonarsource.sonarlint.ls.AnalysisScheduler.SONARCLOUD_TAINT_SOURCE;
import static org.sonarsource.sonarlint.ls.AnalysisScheduler.SONARQUBE_TAINT_SOURCE;
import static org.sonarsource.sonarlint.ls.util.Utils.textRangeWithHashFromTextRange;

class ServerSentEventsTests {
  @TempDir
  Path basedir;
  @RegisterExtension
  SonarLintLogTester logTester = new SonarLintLogTester();
  private Path workspaceFolderPath;
  private Path fileInAWorkspaceFolderPath;
  ConcurrentMap<URI, Optional<ProjectBindingWrapper>> folderBindingCache;
  ConcurrentMap<String, Optional<ConnectedSonarLintEngine>> connectedEngineCacheByConnectionId;
  private ServerSentEventsHandlerService underTest;
  private final SettingsManager settingsManager = mock(SettingsManager.class);
  private final WorkspaceFoldersManager foldersManager = mock(WorkspaceFoldersManager.class);
  private final EnginesFactory enginesFactory = mock(EnginesFactory.class);
  private final ConnectedSonarLintEngine fakeEngine = mock(ConnectedSonarLintEngine.class);
  SonarLintExtendedLanguageClient client = mock(SonarLintExtendedLanguageClient.class);
  private TaintVulnerabilitiesCache taintVulnerabilitiesCache;
  private final DiagnosticPublisher diagnosticPublisher = mock(DiagnosticPublisher.class);
  private static final String CONNECTION_ID = "myServer";
  private static final BackendServiceFacade backendServiceFacade = mock(BackendServiceFacade.class);
  private static final ServerConnectionSettings GLOBAL_SETTINGS = new ServerConnectionSettings(CONNECTION_ID, "http://foo", "token", null, false);
  private static final ServerConnectionSettings GLOBAL_SETTINGS_DISABLED_NOTIFICATIONS = new ServerConnectionSettings(CONNECTION_ID, "http://foo", "token", null, true);
  private static final ServerConnectionSettings GLOBAL_SETTINGS_SONARCLOUD = new ServerConnectionSettings(CONNECTION_ID, "https://sonarcloud.io", "token", "test-org", true);
  private static final String FILE_PHP = "fileInAWorkspaceFolderPath.php";
  private static final String PROJECT_KEY = "myProject";
  private static final String BRANCH_NAME = "main";
  private static final String CURRENT_BRANCH_NAME = "currentBranch";
  private static final String ISSUE_KEY1 = "TEST_ISSUE_KEY1";
  private static final String ISSUE_KEY2 = "TEST_ISSUE_KEY2";
  private static final Instant CREATION_DATE = Instant.now();
  private static final String RULE_KEY = "javasecurity:S3649";
  private static final IssueSeverity ISSUE_SEVERITY = IssueSeverity.BLOCKER;
  private static final IssueSeverity NEW_ISSUE_SEVERITY = IssueSeverity.MINOR;
  private static final RuleType NEW_RULE_TYPE = RuleType.CODE_SMELL;
  private static final RuleType RULE_TYPE = RuleType.VULNERABILITY;
  private static TaintVulnerabilityRaisedEvent.Location MAIN_LOCATION;
  private static final List<TaintVulnerabilityRaisedEvent.Flow> FLOWS = new ArrayList<>();
  ProjectBindingManager projectBindingManager;
  TaintVulnerabilityRaisedNotification taintVulnerabilityRaisedNotification = mock(TaintVulnerabilityRaisedNotification.class);
  WorkspaceFoldersManager workspaceFoldersManager = mock(WorkspaceFoldersManager.class);
  AnalysisScheduler analysisScheduler = mock(AnalysisScheduler.class);

  @BeforeEach
  public void prepare() throws IOException, ExecutionException, InterruptedException {
    workspaceFolderPath = basedir.resolve("myWorkspaceFolder");
    Files.createDirectories(workspaceFolderPath);
    fileInAWorkspaceFolderPath = workspaceFolderPath.resolve(FILE_PHP);
    Files.createFile(fileInAWorkspaceFolderPath);

//    when(fakeEngine.getServerBranches(any(String.class))).thenReturn(new ProjectBranches(Set.of(BRANCH_NAME), BRANCH_NAME));

    folderBindingCache = new ConcurrentHashMap<>();
    connectedEngineCacheByConnectionId = new ConcurrentHashMap<>();
    taintVulnerabilitiesCache = new TaintVulnerabilitiesCache();

    projectBindingManager = new ProjectBindingManager(enginesFactory, foldersManager, settingsManager, client, folderBindingCache,
      null, connectedEngineCacheByConnectionId, taintVulnerabilitiesCache, diagnosticPublisher, backendServiceFacade, mock(OpenNotebooksCache.class));
    projectBindingManager.setBranchResolver(uri -> Optional.of(BRANCH_NAME));

    underTest = new ServerSentEventsHandler(projectBindingManager, taintVulnerabilitiesCache, taintVulnerabilityRaisedNotification, settingsManager, workspaceFoldersManager, analysisScheduler);

    MAIN_LOCATION = new TaintVulnerabilityRaisedEvent.Location(fileInAWorkspaceFolderPath.toUri().toString(),
      "Change this code to not construct SQL queries directly from user-controlled data.",
      new TaintVulnerabilityRaisedEvent.Location.TextRange(1, 2, 3, 4, "blablabla"));
  }

  private void prepareForServerEventTests() {
    mockFileInAFolder();
    var projectBindingWrapperMock = mock(ProjectBindingWrapper.class);
    var projectBinding = mock(ProjectBinding.class);
    when(projectBinding.projectKey()).thenReturn(PROJECT_KEY);
    when(projectBindingWrapperMock.getBinding()).thenReturn(projectBinding);
    folderBindingCache.put(workspaceFolderPath.toUri(), Optional.of(projectBindingWrapperMock));
    when(projectBindingWrapperMock.getEngine()).thenReturn(fakeEngine);
    when(projectBinding.serverPathToIdePath(fileInAWorkspaceFolderPath.toUri().toString())).thenReturn(Optional.of(fileInAWorkspaceFolderPath.toString()));
    when(projectBindingWrapperMock.getConnectionId()).thenReturn(CONNECTION_ID);
  }

  private static WorkspaceSettings newWorkspaceSettingsWithServers(Map<String, ServerConnectionSettings> servers) {
    return new WorkspaceSettings(false, servers, Collections.emptyList(), Collections.emptyList(), Collections.emptyMap(), false, false, "", false);
  }

  private void mockFileInAFolder() {
    var folderWrapper = spy(new WorkspaceFolderWrapper(workspaceFolderPath.toUri(), new WorkspaceFolder(workspaceFolderPath.toUri().toString()), logTester.getLogger()));
    when(foldersManager.findFolderForFile(fileInAWorkspaceFolderPath.toUri())).thenReturn(Optional.of(folderWrapper));
  }
}
