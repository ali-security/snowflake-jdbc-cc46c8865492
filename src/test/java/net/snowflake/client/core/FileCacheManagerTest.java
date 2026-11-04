/*
 * Copyright (c) 2012-2022 Snowflake Computing Inc. All rights reserved.
 */

package net.snowflake.client.core;

import static net.snowflake.client.core.StmtUtil.mapper;
import static net.snowflake.client.jdbc.SnowflakeUtil.systemGetProperty;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.isA;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.snowflake.client.ConditionalIgnoreRule;
import net.snowflake.client.RunningOnWin;
import net.snowflake.client.category.TestCategoryCore;
import net.snowflake.client.jdbc.BaseJDBCTest;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

@Category(TestCategoryCore.class)
public class FileCacheManagerTest extends BaseJDBCTest {

  private static final String CACHE_FILE_NAME = "temporary_credential.json";
  private static final String CACHE_DIR_PROP = "net.snowflake.jdbc.temporaryCredentialCacheDir";
  private static final String CACHE_DIR_ENV = "SF_TEMPORARY_CREDENTIAL_CACHE_DIR";
  private static final long CACHE_EXPIRATION_IN_SECONDS = 86400L;
  private static final long CACHE_FILE_LOCK_EXPIRATION_IN_SECONDS = 60L;

  private static final String TEST_CACHE_DIR_PROP =
      "net.snowflake.jdbc.fileCacheManagerTestCacheDir";

  @Rule public TemporaryFolder tmpFolder = new TemporaryFolder();

  private FileCacheManager fileCacheManager;
  private File cacheFile;

  @Before
  public void setup() throws IOException {
    fileCacheManager =
        FileCacheManager.builder()
            .setCacheDirectorySystemProperty(CACHE_DIR_PROP)
            .setCacheDirectoryEnvironmentVariable(CACHE_DIR_ENV)
            .setBaseCacheFileName(CACHE_FILE_NAME)
            .setCacheExpirationInSeconds(CACHE_EXPIRATION_IN_SECONDS)
            .setCacheFileLockExpirationInSeconds(CACHE_FILE_LOCK_EXPIRATION_IN_SECONDS)
            .build();
    cacheFile = createCacheFile();
  }

  @After
  public void clean() throws IOException {
    if (Files.exists(cacheFile.toPath())) {
      Files.delete(cacheFile.toPath());
    }
  }

  @Test
  @ConditionalIgnoreRule.ConditionalIgnore(condition = RunningOnWin.class)
  public void throwWhenReadCacheFileWithPermissionDifferentThanReadWriteForUserTest()
      throws IOException {
    String[][] testCases = {
      {"rwx------", "false"},
      {"rw-------", "true"},
      {"r-x------", "false"},
      {"r--------", "true"},
      {"rwxrwx---", "false"},
      {"rwxrw----", "false"},
      {"rwxr-x---", "false"},
      {"rwxr-----", "false"},
      {"rwx-wx---", "false"},
      {"rwx-w----", "false"},
      {"rwx--x---", "false"},
      {"rwx---rwx", "false"},
      {"rwx---rw-", "false"},
      {"rwx---r-x", "false"},
      {"rwx---r--", "false"},
      {"rwx----wx", "false"},
      {"rwx----w-", "false"},
      {"rwx-----x", "false"}
    };
    for (String[] testCase : testCases) {
      String permission = testCase[0];
      boolean isSucceed = Boolean.parseBoolean(testCase[1]);
      // fresh cache file for every permission set, as a parameterized test would get
      clean();
      setup();

      fileCacheManager.overrideCacheFile(cacheFile);
      Files.setPosixFilePermissions(
          cacheFile.toPath(), PosixFilePermissions.fromString(permission));
      if (isSucceed) {
        assertNotNull(
            "Reading the cache with permission " + permission + " should succeed",
            fileCacheManager.readCacheFile());
      } else {
        SecurityException ex =
            assertThrows(
                "Reading the cache with permission " + permission + " should be rejected",
                SecurityException.class,
                () -> fileCacheManager.readCacheFile());
        assertTrue(ex.getMessage().contains("is wider than allowed only to the owner"));
      }
    }
  }

  @Test
  @ConditionalIgnoreRule.ConditionalIgnore(condition = RunningOnWin.class)
  public void throwWhenOverrideCacheFileHasDifferentOwnerThanCurrentUserTest() {
    try (MockedStatic<FileUtil> fileUtilMock =
        Mockito.mockStatic(FileUtil.class, Mockito.CALLS_REAL_METHODS)) {
      fileUtilMock.when(() -> FileUtil.getFileOwnerName(isA(Path.class))).thenReturn("anotherUser");
      SecurityException ex =
          assertThrows(SecurityException.class, () -> fileCacheManager.readCacheFile());
      assertTrue(ex.getMessage().contains("The file owner is different than current user"));
    }
  }

  @Test
  @ConditionalIgnoreRule.ConditionalIgnore(condition = RunningOnWin.class)
  public void notThrowForToWidePermissionsWhenOnlyOwnerPermissionsSetFalseTest()
      throws IOException {
    fileCacheManager.setOnlyOwnerPermissions(false);
    Files.setPosixFilePermissions(cacheFile.toPath(), PosixFilePermissions.fromString("rwxrwx---"));
    assertNotNull(fileCacheManager.readCacheFile());
  }

  @Test
  @ConditionalIgnoreRule.ConditionalIgnore(condition = RunningOnWin.class)
  public void throwWhenOverrideCacheFileNotFound() {
    Path wrongPath =
        Paths.get(systemGetProperty("user.home"), ".cache", "snowflake2", "wrongFileName");
    SecurityException ex =
        assertThrows(
            SecurityException.class, () -> fileCacheManager.overrideCacheFile(wrongPath.toFile()));
    assertTrue(
        ex.getMessage()
            .contains(
                "Unable to access the file to check the permissions. Error: java.nio.file.NoSuchFileException:"));
  }

  @Test
  @ConditionalIgnoreRule.ConditionalIgnore(condition = RunningOnWin.class)
  public void throwWhenOverrideCacheFileWithPermissionsWiderThanOwnerTest() throws IOException {
    Files.setPosixFilePermissions(cacheFile.toPath(), PosixFilePermissions.fromString("rw-r--r--"));
    SecurityException ex =
        assertThrows(
            SecurityException.class, () -> fileCacheManager.overrideCacheFile(cacheFile));
    assertTrue(ex.getMessage().contains("is wider than allowed only to the owner"));
  }

  @Test
  @ConditionalIgnoreRule.ConditionalIgnore(condition = RunningOnWin.class)
  public void createCacheFileWithOnlyOwnerPermissionsTest() throws IOException {
    File cacheDir = tmpFolder.newFolder("owner-only-cache");
    System.setProperty(TEST_CACHE_DIR_PROP, cacheDir.getAbsolutePath());
    try {
      FileCacheManager.builder()
          .setCacheDirectorySystemProperty(TEST_CACHE_DIR_PROP)
          .setBaseCacheFileName(CACHE_FILE_NAME)
          .setCacheExpirationInSeconds(CACHE_EXPIRATION_IN_SECONDS)
          .setCacheFileLockExpirationInSeconds(CACHE_FILE_LOCK_EXPIRATION_IN_SECONDS)
          .build();
    } finally {
      System.clearProperty(TEST_CACHE_DIR_PROP);
    }
    Path createdCacheFile = cacheDir.toPath().resolve(CACHE_FILE_NAME);
    assertTrue(Files.exists(createdCacheFile));
    assertEquals(
        Stream.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
            .collect(Collectors.toSet()),
        Files.getPosixFilePermissions(createdCacheFile));
  }

  @Test
  @ConditionalIgnoreRule.ConditionalIgnore(condition = RunningOnWin.class)
  public void notWriteCredentialsToCacheFileWithPermissionsWiderThanOwnerTest()
      throws IOException {
    Files.setPosixFilePermissions(cacheFile.toPath(), PosixFilePermissions.fromString("rw-r--r--"));
    ObjectNode cacheContent = mapper.createObjectNode();
    cacheContent.put("token", "secretTokenValue");
    SecurityException ex =
        assertThrows(
            SecurityException.class, () -> fileCacheManager.writeCacheFile(cacheContent));
    assertTrue(ex.getMessage().contains("is wider than allowed only to the owner"));
    String content = new String(Files.readAllBytes(cacheFile.toPath()), StandardCharsets.UTF_8);
    assertFalse(content.contains("secretTokenValue"));
  }

  @Test
  @ConditionalIgnoreRule.ConditionalIgnore(condition = RunningOnWin.class)
  public void notFallbackToTmpDirWhenHomeDirectoryIsNotSetTest() throws IOException {
    File tmpDir = tmpFolder.newFolder("shared-tmp");
    String originalHome = System.getProperty("user.home");
    String originalTmpDir = System.getProperty("java.io.tmpdir");
    FileCacheManager manager;
    try {
      System.clearProperty("user.home");
      System.setProperty("java.io.tmpdir", tmpDir.getAbsolutePath());
      manager =
          FileCacheManager.builder()
              .setBaseCacheFileName(CACHE_FILE_NAME)
              .setCacheExpirationInSeconds(CACHE_EXPIRATION_IN_SECONDS)
              .setCacheFileLockExpirationInSeconds(CACHE_FILE_LOCK_EXPIRATION_IN_SECONDS)
              .build();
    } finally {
      System.setProperty("user.home", originalHome);
      System.setProperty("java.io.tmpdir", originalTmpDir);
    }
    assertNoCacheCreatedIn(tmpDir, manager);
  }

  @Test
  @ConditionalIgnoreRule.ConditionalIgnore(condition = RunningOnWin.class)
  public void notFallbackToTmpDirWhenHomeDirectoryIsNotWritableTest() throws IOException {
    File homeDir = tmpFolder.newFolder("readonly-home");
    File tmpDir = tmpFolder.newFolder("shared-tmp");
    Files.setPosixFilePermissions(homeDir.toPath(), PosixFilePermissions.fromString("r-x------"));
    String originalHome = System.getProperty("user.home");
    String originalTmpDir = System.getProperty("java.io.tmpdir");
    FileCacheManager manager;
    try {
      // a privileged user can still write to the directory, then the scenario can't be reproduced
      Assume.assumeFalse(homeDir.canWrite());
      System.setProperty("user.home", homeDir.getAbsolutePath());
      System.setProperty("java.io.tmpdir", tmpDir.getAbsolutePath());
      manager =
          FileCacheManager.builder()
              .setBaseCacheFileName(CACHE_FILE_NAME)
              .setCacheExpirationInSeconds(CACHE_EXPIRATION_IN_SECONDS)
              .setCacheFileLockExpirationInSeconds(CACHE_FILE_LOCK_EXPIRATION_IN_SECONDS)
              .build();
    } finally {
      System.setProperty("user.home", originalHome);
      System.setProperty("java.io.tmpdir", originalTmpDir);
      Files.setPosixFilePermissions(
          homeDir.toPath(), PosixFilePermissions.fromString("rwx------"));
    }
    assertNoCacheCreatedIn(tmpDir, manager);
  }

  private void assertNoCacheCreatedIn(File tmpDir, FileCacheManager manager) {
    String[] tmpDirContent = tmpDir.list();
    assertNotNull(tmpDirContent);
    assertEquals(
        "Cache must not be created in the shared temporary directory", 0, tmpDirContent.length);
    assertNull(manager.readCacheFile());
  }

  private File createCacheFile() {
    Path cacheFile =
        Paths.get(systemGetProperty("user.home"), ".cache", "snowflake2", CACHE_FILE_NAME);
    try {
      if (Files.exists(cacheFile)) {
        Files.delete(cacheFile);
      }
      Files.createDirectories(cacheFile.getParent());
      Files.createFile(
          cacheFile,
          PosixFilePermissions.asFileAttribute(
              Stream.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
                  .collect(Collectors.toSet())));
      ObjectNode cacheContent = mapper.createObjectNode();
      cacheContent.put("token", "tokenValue");
      fileCacheManager.overrideCacheFile(cacheFile.toFile());
      fileCacheManager.writeCacheFile(cacheContent);
      return cacheFile.toFile();

    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
