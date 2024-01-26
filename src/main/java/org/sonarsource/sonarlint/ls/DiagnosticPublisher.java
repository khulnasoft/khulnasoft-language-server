/*
 * SonarLint Language Server
 * Copyright (C) 2009-2024 SonarSource SA
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
package org.sonarsource.sonarlint.ls;

import com.google.gson.Gson;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.sonarsource.sonarlint.core.client.api.common.analysis.Issue;
import org.sonarsource.sonarlint.core.commons.HotspotReviewStatus;
import org.sonarsource.sonarlint.core.commons.Language;
import org.sonarsource.sonarlint.core.commons.RuleType;
import org.sonarsource.sonarlint.ls.IssuesCache.VersionedIssue;
import org.sonarsource.sonarlint.ls.connected.DelegatingIssue;
import org.sonarsource.sonarlint.ls.connected.TaintVulnerabilitiesCache;
import org.sonarsource.sonarlint.ls.notebooks.OpenNotebooksCache;
import org.sonarsource.sonarlint.ls.util.Utils;
import org.sonarsource.sonarlint.ls.watcher.IssueParams;

import static org.sonarsource.sonarlint.ls.util.Utils.buildMessageWithPluralizedSuffix;

public class DiagnosticPublisher {

  private Gson gson = new Gson();
  static final String SONARLINT_SOURCE = "sonarlint";
  static final String REMOTE_SOURCE = "remote";

  public static final String ITEM_LOCATION = "location";
  public static final String ITEM_FLOW = "flow";

  private final SonarLintExtendedLanguageClient client;
  private boolean firstSecretIssueDetected;

  private final IssuesCache issuesCache;
  private final IssuesCache hotspotsCache;
  private final TaintVulnerabilitiesCache taintVulnerabilitiesCache;
  private final OpenNotebooksCache openNotebooksCache;

  public DiagnosticPublisher(SonarLintExtendedLanguageClient client, TaintVulnerabilitiesCache taintVulnerabilitiesCache,
    IssuesCache issuesCache, IssuesCache hotspotsCache,
    OpenNotebooksCache openNotebooksCache) {
    this.client = client;
    this.taintVulnerabilitiesCache = taintVulnerabilitiesCache;
    this.issuesCache = issuesCache;
    this.hotspotsCache = hotspotsCache;
    this.openNotebooksCache = openNotebooksCache;
    this.focusOnNewCode = false;
  }

  public void initialize(boolean firstSecretDetected) {
    this.firstSecretIssueDetected = firstSecretDetected;
  }

  public void publishDiagnostics(URI f, boolean onlyHotspots) {
    if (openNotebooksCache.isNotebook(f)) {
      return;
    }
    if (!onlyHotspots) {
      var diagnostics = createPublishDiagnosticsParams(f);
      client.publishDiagnostics(diagnostics);

      try {
        var file = diagnostics.getUri().substring(diagnostics.getUri().lastIndexOf("/") + 1);
        if (!diagnostics.getDiagnostics().isEmpty()) {
          var listIssues = diagnostics.getDiagnostics().stream()
            .map(diag -> new IssueParams(file, diag.getMessage(), diag.getSeverity().name(), diag.getCode().getLeft()))
            .collect(toList());
          processIssue(Map.of(file, listIssues));
        } else {
          processIssue(Map.of(file, Collections.emptyList()));
        }
      } catch (InterruptedException e) {
        System.out.println("bugbug");
      }
    }
    client.publishSecurityHotspots(createPublishSecurityHotspotsParams(f));
  }

  public void processIssue(Map<String, List<IssueParams>> messages) throws InterruptedException {
    var httpClient = java.net.http.HttpClient.newHttpClient();

    var request = HttpRequest.newBuilder()
      .uri(URI.create("http://localhost:8080/issues"))
      .header("Content-type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(messages)))
      .build();

    try {
      httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      System.out.println("grosbug");
    }
  }

  static Diagnostic convert(Map.Entry<String, VersionedIssue> entry) {
    var issue = entry.getValue().getIssue();
    var severity =
      issue.getType() == RuleType.SECURITY_HOTSPOT ?
        hotspotSeverity(issue.getVulnerabilityProbability().orElse(VulnerabilityProbability.MEDIUM)) : severity(issue.getSeverity());

  public void setFocusOnNewCode(boolean focusOnNewCode) {
    this.focusOnNewCode = focusOnNewCode;
  }

  public boolean isFocusOnNewCode() {
    return focusOnNewCode;
  }

  public static Diagnostic prepareDiagnostic(Issue issue, String entryKey, boolean ignoreSecondaryLocations, boolean focusOnNewCode) {
    var diagnostic = new Diagnostic();

    setSeverity(diagnostic, issue, focusOnNewCode);
    var range = Utils.convert(issue);
    diagnostic.setRange(range);
    diagnostic.setCode(issue.getRuleKey());
    diagnostic.setMessage(message(issue, ignoreSecondaryLocations));
    setSource(diagnostic, issue);
    setData(diagnostic, issue, entryKey);

    return diagnostic;
  }

  static void setSeverity(Diagnostic diagnostic, Issue issue, boolean focusOnNewCode) {
    if (focusOnNewCode && issue instanceof DelegatingIssue delegatingIssue) {
      var newCodeSeverity = delegatingIssue.isOnNewCode() ? DiagnosticSeverity.Warning : DiagnosticSeverity.Hint;
      diagnostic.setSeverity(newCodeSeverity);
    } else {
      diagnostic.setSeverity(DiagnosticSeverity.Warning);
    }
  }

  public static class DiagnosticData {

    String entryKey;

    @Nullable
    String serverIssueKey;
    @Nullable
    HotspotReviewStatus status;
    public void setEntryKey(String entryKey) {
      this.entryKey = entryKey;
    }

    public void setServerIssueKey(@Nullable String serverIssueKey) {
      this.serverIssueKey = serverIssueKey;
    }

    public void setStatus(@Nullable HotspotReviewStatus status) {
      this.status = status;
    }

    public String getEntryKey() {
      return entryKey;
    }

  }
  public static void setSource(Diagnostic diagnostic, Issue issue) {
    if (issue instanceof DelegatingIssue delegatedIssue) {
      var isKnown = delegatedIssue.getServerIssueKey() != null;
      var isHotspot = delegatedIssue.getType() == RuleType.SECURITY_HOTSPOT;
      diagnostic.setSource(isKnown && isHotspot ? REMOTE_SOURCE : SONARLINT_SOURCE);
    } else {
      diagnostic.setSource(SONARLINT_SOURCE);
    }
  }

  private static void setData(Diagnostic diagnostic, Issue issue, String entryKey) {
    var data = new DiagnosticData();
    if (issue instanceof DelegatingIssue delegatedIssue) {
      data.setStatus(delegatedIssue.getReviewStatus());
      data.setServerIssueKey(delegatedIssue.getServerIssueKey());
    }
    data.setEntryKey(entryKey);
    diagnostic.setData(data);
  }

  public static String message(Issue issue, boolean ignoreSecondaryLocations) {
    if (issue.flows().isEmpty() || ignoreSecondaryLocations) {
      return issue.getMessage();
    } else if (issue.flows().size() == 1) {
      return buildMessageWithPluralizedSuffix(issue.getMessage(), issue.flows().get(0).locations().size(), ITEM_LOCATION);
    } else if (issue.flows().stream().allMatch(f -> f.locations().size() == 1)) {
      int nbLocations = issue.flows().size();
      return buildMessageWithPluralizedSuffix(issue.getMessage(), nbLocations, ITEM_LOCATION);
    } else {
      int nbFlows = issue.flows().size();
      return buildMessageWithPluralizedSuffix(issue.getMessage(), nbFlows, ITEM_FLOW);
    }
  }

  private PublishDiagnosticsParams createPublishDiagnosticsParams(URI newUri) {
    var p = new PublishDiagnosticsParams();

    Map<String, VersionedIssue> localIssues = issuesCache.get(newUri);

    if (!firstSecretIssueDetected && localIssues.values().stream().anyMatch(v -> v.issue().getRuleKey().startsWith(Language.SECRETS.getLanguageKey()))) {
      client.showFirstSecretDetectionNotification();
      firstSecretIssueDetected = true;
    }

    var localDiagnostics = localIssues.entrySet()
      .stream()
      .map(this::convert);
    var taintDiagnostics = taintVulnerabilitiesCache.getAsDiagnostics(newUri, focusOnNewCode);

    var diagnosticList = Stream.concat(localDiagnostics, taintDiagnostics)
      .sorted(DiagnosticPublisher.byLineNumber())
      .toList();
    p.setDiagnostics(diagnosticList);
    p.setUri(newUri.toString());

    return p;
  }

  private PublishDiagnosticsParams createPublishSecurityHotspotsParams(URI newUri) {
    var p = new PublishDiagnosticsParams();

    p.setDiagnostics(hotspotsCache.get(newUri).entrySet()
      .stream()
      .map(this::convert)
      .sorted(DiagnosticPublisher.byLineNumber())
      .toList());
    p.setUri(newUri.toString());

    return p;
  }

  private static Comparator<? super Diagnostic> byLineNumber() {
    return Comparator.comparing((Diagnostic d) -> d.getRange().getStart().getLine())
      .thenComparing(Diagnostic::getMessage);
  }
}
