/*
 * SonarQube
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
package org.sonar.scanner.scm;

import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;
import com.tngtech.java.junit.dataprovider.UseDataProvider;
import java.util.Optional;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.slf4j.event.Level;
import org.sonar.api.CoreProperties;
import org.sonar.api.batch.scm.ScmProvider;
import org.sonar.api.config.Configuration;
import org.sonar.api.notifications.AnalysisWarnings;
import org.sonar.api.utils.MessageException;
import org.sonar.api.testfixtures.log.LogTester;
import org.sonar.core.config.ScannerProperties;
import org.sonar.scanner.fs.InputModuleHierarchy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;
import static org.sonar.scanner.scm.ScmConfiguration.MESSAGE_SCM_EXCLUSIONS_IS_DISABLED_BY_CONFIGURATION;
import static org.sonar.scanner.scm.ScmConfiguration.MESSAGE_SCM_STEP_IS_DISABLED_BY_CONFIGURATION;

@RunWith(DataProviderRunner.class)
public class ScmConfigurationTest {

  private final InputModuleHierarchy inputModuleHierarchy = mock(InputModuleHierarchy.class, withSettings().defaultAnswer(Answers.RETURNS_MOCKS));
  private final AnalysisWarnings analysisWarnings = mock(AnalysisWarnings.class);
  private final Configuration settings = mock(Configuration.class);

  private final String scmProviderKey = "dummyScmProviderKey";
  private final ScmProvider scmProvider = mock(ScmProvider.class);

  private final ScmConfiguration underTest;

  @Rule
  public LogTester logTester = new LogTester();

  public ScmConfigurationTest() {
    when(scmProvider.key()).thenReturn(scmProviderKey);

    underTest = new ScmConfiguration(inputModuleHierarchy, settings, analysisWarnings, scmProvider);
  }

  @Test
  public void do_not_register_warning_when_success_to_autodetect_scm_provider() {
    when(scmProvider.supports(any())).thenReturn(true);

    underTest.start();

    assertThat(underTest.provider()).isNotNull();
    verifyNoInteractions(analysisWarnings);
  }

  @Test
  public void no_provider_if_no_provider_is_available() {
    ScmConfiguration underTest = new ScmConfiguration(inputModuleHierarchy, settings, analysisWarnings);
    assertThat(underTest.provider()).isNull();
    verifyNoInteractions(analysisWarnings);
  }

  @Test
  public void register_warning_when_fail_to_detect_scm_provider() {
    underTest.start();

    assertThat(underTest.provider()).isNull();
    verify(analysisWarnings).addUnique(anyString());
  }

  @Test
  public void log_when_disabled() {
    logTester.setLevel(Level.DEBUG);
    when(settings.getBoolean(CoreProperties.SCM_DISABLED_KEY)).thenReturn(Optional.of(true));

    underTest.start();

    assertThat(logTester.logs()).contains(MESSAGE_SCM_STEP_IS_DISABLED_BY_CONFIGURATION);
  }

  @Test
  public void log_when_exclusion_is_disabled() {
    when(settings.getBoolean(CoreProperties.SCM_EXCLUSIONS_DISABLED_KEY)).thenReturn(Optional.of(true));

    underTest.start();

    assertThat(logTester.logs()).contains(MESSAGE_SCM_EXCLUSIONS_IS_DISABLED_BY_CONFIGURATION);
  }

  @Test
  @UseDataProvider("scmDisabledProperty")
  public void exclusion_is_disabled_by_property(boolean scmDisabled, boolean scmExclusionsDisabled, boolean isScmExclusionDisabled) {
    when(settings.getBoolean(CoreProperties.SCM_DISABLED_KEY)).thenReturn(Optional.of(scmDisabled));
    when(settings.getBoolean(CoreProperties.SCM_EXCLUSIONS_DISABLED_KEY)).thenReturn(Optional.of(scmExclusionsDisabled));

    underTest.start();

    assertThat(underTest.isExclusionDisabled()).isEqualTo(isScmExclusionDisabled);
  }

  @DataProvider
  public static Object[][] scmDisabledProperty() {
    return new Object[][] {
      {true, true, true},
      {true, false, true},
      {false, true, true},
      {false, false, false}
    };
  }

  @Test
  public void fail_when_multiple_scm_providers_claim_support() {
    when(scmProvider.supports(any())).thenReturn(true);
    when(scmProvider.key()).thenReturn("key1", "key2");

    ScmProvider[] providers = {scmProvider, scmProvider};
    ScmConfiguration underTest = new ScmConfiguration(inputModuleHierarchy, settings, analysisWarnings, providers);

    assertThatThrownBy(() -> underTest.start())
      .isInstanceOf(MessageException.class)
      .hasMessageContaining("SCM provider autodetection failed. "
        + "Both key2 and key2 claim to support this project. "
        + "Please use \"sonar.scm.provider\" to define SCM of your project.");
  }

  @Test
  public void fail_when_considerOldScmUrl_finds_invalid_provider_in_link() {
    when(settings.get(ScannerProperties.LINKS_SOURCES_DEV)).thenReturn(Optional.of("scm:invalid"));

    assertThatThrownBy(() -> underTest.start())
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("no SCM provider found for this key");
  }

  @Test
  public void set_provider_from_valid_link() {
    when(settings.get(ScannerProperties.LINKS_SOURCES_DEV)).thenReturn(Optional.of("scm:" + scmProviderKey));

    underTest.start();

    assertThat(underTest.provider()).isSameAs(scmProvider);
  }

  @Test
  @UseDataProvider("malformedScmLinks")
  public void dont_set_provider_from_links_if_malformed(String link) {
    when(settings.get(ScannerProperties.LINKS_SOURCES_DEV)).thenReturn(Optional.of(link));

    underTest.start();

    assertThat(underTest.provider()).isNull();
  }

  @DataProvider
  public static Object[][] malformedScmLinks() {
    return new Object[][] {
      {"invalid prefix"},
      {"scm"},
      {"scm:"}
    };
  }
}
