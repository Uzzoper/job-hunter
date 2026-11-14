package com.juanperuzzo.job_hunter.application.port.in;

/**
 * Account identity of the authenticated user, resolved separately from the
 * resume-extracted contact fields (issue #92 slice a): {@code email} is the
 * login account address, {@code name} the account display name — neither is
 * editable by this slice.
 */
public record AccountIdentity(String name, String email) {}
