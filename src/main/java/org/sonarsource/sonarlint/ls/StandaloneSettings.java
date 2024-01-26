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
package org.sonarsource.sonarlint.ls;

public class StandaloneSettings {
  // KIRILL
  //  public static final String NODE_PATH = "/usr/local/bin/node";
  // NICOLAS
  public static final String NODE_PATH = "/home/nicolas.quinquenel/.nvm/versions/node/v18.13.0/bin/node";
  private final String watchDirPath;
  private final String workspaceFolderUri;

  public StandaloneSettings(String workspaceFolderPath) {
    this.watchDirPath = workspaceFolderPath;
    this.workspaceFolderUri = "file://" + workspaceFolderPath;
  }

  public String getWatchDirPath() {
    return watchDirPath;
  }

  public String getWorkspaceFolderUri() {
    return workspaceFolderUri;
  }

  public String getWorkspaceFolderName() {
    return getWatchDirPath().substring(getWatchDirPath().lastIndexOf("/") + 1);
  }



}
