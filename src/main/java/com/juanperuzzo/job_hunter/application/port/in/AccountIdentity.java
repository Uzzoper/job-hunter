package com.juanperuzzo.job_hunter.application.port.in;

/**
 * Operator identity shown separately from resume-extracted contact fields:
 * {@code email} and {@code name} are stored in the single profile and can be
 * corrected independently from extracted contact details.
 */
public record AccountIdentity(String name, String email) {}
