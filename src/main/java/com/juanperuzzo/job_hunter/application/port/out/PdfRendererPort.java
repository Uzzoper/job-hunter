package com.juanperuzzo.job_hunter.application.port.out;

/**
 * Outbound port for rendering HTML documents into PDF bytes.
 */
public interface PdfRendererPort {

    /**
     * Renders the given HTML document into PDF bytes.
     *
     * @param html the HTML document to render
     * @return the rendered PDF bytes
     */
    byte[] renderPdf(String html);
}