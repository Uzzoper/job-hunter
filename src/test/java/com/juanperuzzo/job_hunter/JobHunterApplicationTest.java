package com.juanperuzzo.job_hunter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JobHunterApplicationTest {

	@Test
	@DisplayName("ensureSqliteDirectoryExists when parent missing should create it")
	void ensureSqliteDirectoryExists_whenParentMissing_shouldCreateIt(@TempDir Path tmp) {
		Path db = tmp.resolve("nested/deeper/jobhunter.db");

		JobHunterApplication.ensureSqliteDirectoryExists("jdbc:sqlite:" + db);

		assertTrue(Files.isDirectory(db.getParent()));
	}

	@Test
	@DisplayName("ensureSqliteDirectoryExists when directory exists should not fail")
	void ensureSqliteDirectoryExists_whenDirectoryExists_shouldNotFail(@TempDir Path tmp) throws IOException {
		Path db = tmp.resolve("data/jobhunter.db");
		Files.createDirectories(db.getParent());

		JobHunterApplication.ensureSqliteDirectoryExists("jdbc:sqlite:" + db);

		assertTrue(Files.isDirectory(db.getParent()));
	}

	@Test
	@DisplayName("ensureSqliteDirectoryExists when url is not sqlite should do nothing")
	void ensureSqliteDirectoryExists_whenUrlIsNotSqlite_shouldDoNothing(@TempDir Path tmp) {
		Path dir = tmp.resolve("should-not-exist");

		JobHunterApplication.ensureSqliteDirectoryExists("jdbc:postgresql://localhost:5432/jobhunter");

		assertFalse(Files.exists(dir));
	}

	@Test
	@DisplayName("ensureSqliteDirectoryExists when url has query params should still create parent")
	void ensureSqliteDirectoryExists_whenUrlHasQueryParams_shouldStillCreateParent(@TempDir Path tmp) {
		Path db = tmp.resolve("data/jobhunter.db");

		JobHunterApplication.ensureSqliteDirectoryExists("jdbc:sqlite:" + db + "?journal_mode=WAL");

		assertTrue(Files.isDirectory(db.getParent()));
	}
}
