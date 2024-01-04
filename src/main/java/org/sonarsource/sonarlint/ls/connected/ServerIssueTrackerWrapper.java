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
package org.sonarsource.sonarlint.ls.connected;

import com.google.common.collect.Streams;
import java.net.URI;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import javax.annotation.CheckForNull;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.jetbrains.annotations.NotNull;
import org.sonarsource.sonarlint.core.client.legacy.analysis.Issue;
import org.sonarsource.sonarlint.core.client.legacy.analysis.RawIssue;
import org.sonarsource.sonarlint.core.client.legacy.analysis.RawIssueListener;
import org.sonarsource.sonarlint.core.client.legacy.analysis.SonarLintAnalysisEngine;
import org.sonarsource.sonarlint.core.commons.RuleType;
import org.sonarsource.sonarlint.core.issuetracking.CachingIssueTracker;
import org.sonarsource.sonarlint.core.issuetracking.InMemoryIssueTrackerCache;
import org.sonarsource.sonarlint.core.issuetracking.IssueTrackerCache;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.tracking.ClientTrackedFindingDto;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.tracking.LineWithHashDto;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.tracking.LocalOnlyIssueDto;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.tracking.ServerMatchedIssueDto;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.tracking.TextRangeWithHashDto;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.tracking.TrackWithServerIssuesParams;
import org.sonarsource.sonarlint.core.serverapi.EndpointParams;
import org.sonarsource.sonarlint.ls.AnalysisClientInputFile;
import org.sonarsource.sonarlint.ls.backend.BackendServiceFacade;
import org.sonarsource.sonarlint.ls.folders.WorkspaceFoldersManager;
import org.sonarsource.sonarlint.ls.log.LanguageClientLogOutput;
import org.sonarsource.sonarlint.ls.util.Utils;

import static java.util.function.Predicate.not;
import static org.sonarsource.sonarlint.ls.util.FileUtils.getTextRangeContentOfFile;

public class ServerIssueTrackerWrapper {

  private final SonarLintAnalysisEngine engine;
  private final EndpointParams endpointParams;
  private final Supplier<String> getReferenceBranchNameForFolder;
  private final BackendServiceFacade backend;
  private final LanguageClientLogOutput logOutput;
  private final WorkspaceFoldersManager workspaceFoldersManager;

  private final IssueTrackerCache<Issue> issueTrackerCache;
  private final IssueTrackerCache<Issue> hotspotsTrackerCache;
  private final CachingIssueTracker cachingIssueTracker;
  private final CachingIssueTracker cachingHotspotsTracker;
  private final String projectKey;

  ServerIssueTrackerWrapper(SonarLintAnalysisEngine engine, EndpointParams endpointParams,
    String projectKey, Supplier<String> getReferenceBranchNameForFolder,
    BackendServiceFacade backend, WorkspaceFoldersManager workspaceFoldersManager, LanguageClientLogOutput logOutput) {
    this.engine = engine;
    this.endpointParams = endpointParams;
    this.projectKey = projectKey;
    this.getReferenceBranchNameForFolder = getReferenceBranchNameForFolder;
    this.workspaceFoldersManager = workspaceFoldersManager;
    this.backend = backend;
    this.logOutput = logOutput;
  }

  public void matchAndTrack(String filePath, Collection<RawIssue> issues, RawIssueListener issueListener, boolean shouldFetchServerIssues) {
    if (issues.isEmpty()) {
      return;
    }

    // TODO call backend IssueTrackingService.trackWithServerIssues() or another method for hostspots in HotspotTrackingService
//    if (shouldFetchServerIssues) {
//      tracker.update(engine, projectBinding, getReferenceBranchNameForFolder.get(), Collections.singleton(filePath));
//    } else {
//      tracker.update(engine, projectBinding, getReferenceBranchNameForFolder.get(), Collections.singleton(filePath));
//    }

    Optional<URI> workspaceFolderUri = getWorkspaceFolderUri(issues, workspaceFoldersManager);
    workspaceFolderUri.ifPresent(uri -> matchAndTrackIssues(filePath, issueListener, shouldFetchServerIssues, issues, uri));
    hotspotsTrackerCache.getLiveOrFail(filePath).stream()
      .filter(not(Trackable::isResolved))
      .forEach(trackable -> issueListener.handle(new DelegatingIssue(trackable)));
  }

  private void matchAndTrackIssues(String filePath, RawIssueListener issueListener, boolean shouldFetchServerIssues,
    Collection<RawIssue> rawIssues, URI workspaceFolderUri) {
    var issuesByFilepath = getClientTrackedIssuesByServerRelativePath(filePath, rawIssues);
    var trackWithServerIssuesResponse = Utils.safelyGetCompletableFuture(backend.getBackendService().matchIssues(
      new TrackWithServerIssuesParams(workspaceFolderUri.toString(), issuesByFilepath, shouldFetchServerIssues)
    ), logOutput);
    trackWithServerIssuesResponse.ifPresentOrElse(
      // TODO migrate to new DTO
      r -> matchAndTrackIssues(Path.of(filePath), issueListener, rawIssues, r.getIssuesByServerRelativePath()),
      () -> rawIssues.forEach(issueListener::handle)
    );
  }

  private static void matchAndTrackIssues(Path filePath, RawIssueListener issueListener, Collection<RawIssue> rawIssues,
    Map<Path, List<Either<ServerMatchedIssueDto, LocalOnlyIssueDto>>> issuesByServerRelativePath) {
    //
    var eitherList = issuesByServerRelativePath.getOrDefault(filePath, Collections.emptyList());
    Streams.zip(rawIssues.stream(), eitherList.stream(), (issue, either) -> {
        if (either.isLeft()) {
          var serverIssue = either.getLeft();
          var issueSeverity = serverIssue.getOverriddenSeverity() == null ? issue.getSeverity() : serverIssue.getOverriddenSeverity();
          return new DelegatingIssue(issue, serverIssue.getId(), serverIssue.isResolved(),
            org.sonarsource.sonarlint.core.commons.IssueSeverity.valueOf(issueSeverity.name()), serverIssue.getServerKey(), serverIssue.isOnNewCode());
        } else {
          var localIssue = either.getRight();
          return new DelegatingIssue(issue, localIssue.getId(), localIssue.getResolutionStatus() != null, true);
        }
      })
      .filter(not(DelegatingIssue::isResolved))
      .forEach(issueListener::handle);
  }


  @NotNull
  private static Map<Path, List<ClientTrackedFindingDto>> getClientTrackedIssuesByServerRelativePath(String filePath, Collection<RawIssue> issueTrackables) {
    var clientTrackedIssueDtos = issueTrackables.stream().map(ServerIssueTrackerWrapper::createClientTrackedIssueDto).toList();
    return Map.of(Path.of(filePath), clientTrackedIssueDtos);
  }

  static Optional<URI> getWorkspaceFolderUri(Collection<RawIssue> issues, WorkspaceFoldersManager workspaceFoldersManager) {
    var anIssue = issues.stream().findFirst();
    if (anIssue.isPresent()) {
      var inputFile = anIssue.get().getInputFile();
      if (inputFile != null) {
        var folderForFile = workspaceFoldersManager.findFolderForFile(inputFile.uri());
        if (folderForFile.isPresent()) {
          return Optional.of(folderForFile.get().getUri());
        }
      }
    }
    return Optional.empty();
  }

  @NotNull
  private static ClientTrackedFindingDto createClientTrackedIssueDto(RawIssue issue) {
    return new ClientTrackedFindingDto(null, issue.getSeverity().toString(), createTextRangeWithHashDto(issue), createLineWithHashDto(issue), issue.getRuleKey(), issue.getMessage());
  }

  @CheckForNull
  static LineWithHashDto createLineWithHashDto(RawIssue issue) {
    return issue.getLine() != null ? new LineWithHashDto(issue.getLine(), issue.getLineHash()) : null;
  }

  @CheckForNull
  private static TextRangeWithHashDto createTextRangeWithHashDto(RawIssue issue) {
    var textRangeWithHash = issue.getTextRange();
    if (textRangeWithHash != null) {
      return new TextRangeWithHashDto(textRangeWithHash.getStartLine(), textRangeWithHash.getStartLineOffset(),
        textRangeWithHash.getEndLine(), textRangeWithHash.getEndLineOffset(), textRangeWithHash.getHash());
    }
    return null;
  }

  private static Collection<Trackable> toHotspotTrackables(Collection<Issue> issues) {
    return issues.stream().filter(it -> it.getType() == RuleType.SECURITY_HOTSPOT)
      .map(IssueTrackable::new).map(Trackable.class::cast).toList();
  }
}
