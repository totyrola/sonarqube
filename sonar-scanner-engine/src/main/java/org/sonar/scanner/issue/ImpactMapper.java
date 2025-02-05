/*
 * SonarQube
 * Copyright (C) 2009-2025 SonarSource SA
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
package org.sonar.scanner.issue;

import org.sonar.api.issue.impact.Severity;
import org.sonar.scanner.protocol.output.ScannerReport;

public class ImpactMapper {
  private ImpactMapper() {
  }

  public static ScannerReport.ImpactSeverity mapImpactSeverity(Severity severity) {
    return switch (severity) {
      case BLOCKER -> ScannerReport.ImpactSeverity.ImpactSeverity_BLOCKER;
      case HIGH -> ScannerReport.ImpactSeverity.ImpactSeverity_HIGH;
      case MEDIUM -> ScannerReport.ImpactSeverity.ImpactSeverity_MEDIUM;
      case LOW -> ScannerReport.ImpactSeverity.ImpactSeverity_LOW;
      case INFO -> ScannerReport.ImpactSeverity.ImpactSeverity_INFO;
    };
  }
}
