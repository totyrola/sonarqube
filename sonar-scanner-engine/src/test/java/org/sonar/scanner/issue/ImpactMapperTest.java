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

import org.junit.jupiter.api.Test;
import org.sonar.api.issue.impact.Severity;
import org.sonar.scanner.protocol.output.ScannerReport;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ImpactMapperTest {

  @Test
  void mapImpactSeverity_shouldReturnExpectedValue() {
    assertEquals(ScannerReport.ImpactSeverity.ImpactSeverity_BLOCKER, ImpactMapper.mapImpactSeverity(Severity.BLOCKER));
    assertEquals(ScannerReport.ImpactSeverity.ImpactSeverity_HIGH, ImpactMapper.mapImpactSeverity(Severity.HIGH));
    assertEquals(ScannerReport.ImpactSeverity.ImpactSeverity_MEDIUM, ImpactMapper.mapImpactSeverity(Severity.MEDIUM));
    assertEquals(ScannerReport.ImpactSeverity.ImpactSeverity_LOW, ImpactMapper.mapImpactSeverity(Severity.LOW));
    assertEquals(ScannerReport.ImpactSeverity.ImpactSeverity_INFO, ImpactMapper.mapImpactSeverity(Severity.INFO));
  }

}
