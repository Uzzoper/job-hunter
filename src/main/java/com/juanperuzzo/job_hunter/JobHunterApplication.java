package com.juanperuzzo.job_hunter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class JobHunterApplication {

	private static final String DEFAULT_DB_URL = "jdbc:sqlite:./data/jobhunter.db";

	public static void main(String[] args) {
		ensureSqliteDirectoryExists(System.getenv().getOrDefault("DB_URL", DEFAULT_DB_URL));
		SpringApplication.run(JobHunterApplication.class, args);
	}

	/**
	 * SQLite does not create parent directories, so a missing {@code data/} folder
	 * fails startup with an opaque {@code SQLITE_CANTOPEN}. Creates the parent
	 * directory of the SQLite database file before Spring boots. Non-SQLite URLs
	 * (e.g. a future external database) are ignored.
	 *
	 * @param dbUrl the JDBC URL from {@code DB_URL} or the default
	 */
	static void ensureSqliteDirectoryExists(String dbUrl) {
		if (dbUrl == null || !dbUrl.startsWith("jdbc:sqlite:")) {
			return;
		}
		String file = dbUrl.substring("jdbc:sqlite:".length()).split("\\?")[0].strip();
		if (file.isEmpty()) {
			return;
		}
		Path parent = Path.of(file).toAbsolutePath().normalize().getParent();
		if (parent == null) {
			return;
		}
		try {
			Files.createDirectories(parent);
		} catch (IOException e) {
			System.err.println("Could not create database directory " + parent + ": " + e.getMessage());
		}
	}

}
