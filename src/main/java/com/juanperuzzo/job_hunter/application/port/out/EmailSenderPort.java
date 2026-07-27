package com.juanperuzzo.job_hunter.application.port.out;

public interface EmailSenderPort {
    void send(String to, String subject, String body);
}
