package com.juanperuzzo.job_hunter.unit.application;

import com.juanperuzzo.job_hunter.application.service.WorkModelSignals;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the single shared work-model vocabulary used by both
 * {@code JobPreferenceScorer} (scoring modifier) and the opt-in
 * {@code remoteOnly} listing filter
 * (doc: docs/specs/list-jobs-filter.md).
 */
@DisplayName("WorkModelSignals tests")
class WorkModelSignalsTest {

    // ---------- Remote ----------

    @Test
    @DisplayName("isRemote should detect provider labels in either case after accent folding")
    void isRemote_whenProviderLabels_shouldMatch() {
        assertTrue(WorkModelSignals.isRemote("Vaga home office"));
        assertTrue(WorkModelSignals.isRemote("Vaga em regime REMOTO"));
        assertTrue(WorkModelSignals.isRemote("Híbrido com 2 dias de home office"));
    }

    @Test
    @DisplayName("isRemote should tolerate irregular whitespace in multi-word terms")
    void isRemote_whenIrregularWhitespace_shouldCollapseAndMatch() {
        assertTrue(WorkModelSignals.isRemote("100% home  office com benefícios"));
        assertTrue(WorkModelSignals.isRemote("work   from   home"));
    }

    @Test
    @DisplayName("isRemote should not match a term embedded inside a longer word")
    void isRemote_whenTermEmbeddedInWord_shouldNotMatch() {
        assertFalse(WorkModelSignals.isRemote("remotamentesemnegacao"),
                "embedded 'remoto'/'remotamente' must not count without a word boundary");
        assertFalse(WorkModelSignals.isRemote("homeofficeextra"),
                "embedded 'homeoffice' must not count without a word boundary");
    }

    @Test
    @DisplayName("isRemote should honor every negation variant after accent folding")
    void isRemote_whenNegationVariants_shouldReturnFalse() {
        assertFalse(WorkModelSignals.isRemote("100% presencial — não é remoto"));
        assertFalse(WorkModelSignals.isRemote("100% presencial — nao e remoto"));
        assertFalse(WorkModelSignals.isRemote("Atuação presencial — não remoto"));
        assertFalse(WorkModelSignals.isRemote("Vaga sem remoto, atuação 100% presencial"));
        assertFalse(WorkModelSignals.isRemote("Posição presencial — not a remote position"));
        assertFalse(WorkModelSignals.isRemote("Posição presencial — not remote"));
    }

    // ---------- Onsite ----------

    @Test
    @DisplayName("isOnsite should match the presencial/onsite labels and not an embedded variant")
    void isOnsite_whenScorerLabels_shouldMatch() {
        assertTrue(WorkModelSignals.isOnsite("Vaga Presencial"));
        assertTrue(WorkModelSignals.isOnsite("Trabalho 100% onsite"));
        assertTrue(WorkModelSignals.isOnsite("Presencial com on-site opcional"));
        assertFalse(WorkModelSignals.isOnsite("presencialmenteextra"),
                "embedded 'presencial' must not count without a word boundary");
    }

    // ---------- Hybrid ----------

    @Test
    @DisplayName("isHybrid should match accented and unaccented hybrid labels")
    void isHybrid_whenAccentedOrUnaccented_shouldMatch() {
        assertTrue(WorkModelSignals.isHybrid("Modelo híbrido"));
        assertTrue(WorkModelSignals.isHybrid("Modelo hibrido"));
        assertTrue(WorkModelSignals.isHybrid("Regime hybrid"));
        assertFalse(WorkModelSignals.isHybrid("hibridoinventado"),
                "embedded 'hibrido' must not count without a word boundary");
    }

    // ---------- remoteOnly composition ----------

    @Test
    @DisplayName("isRemoteOnly should keep only explicit remote texts with no onsite/hybrid/negation conflict")
    void isRemoteOnly_whenMixedSignals_shouldBeExclusive() {
        assertTrue(WorkModelSignals.isRemoteOnly("Vaga 100% home office"));
        assertFalse(WorkModelSignals.isRemoteOnly("Híbrido com 2 dias de home office"));
        assertFalse(WorkModelSignals.isRemoteOnly("Presencial, sem remoto"));
        assertFalse(WorkModelSignals.isRemoteOnly("Trabalho remoto e também presencial"));
        assertFalse(WorkModelSignals.isRemoteOnly("Vaga para time de plataforma, sem sinal de modelo"));
        assertFalse(WorkModelSignals.isRemoteOnly(""));
        assertFalse(WorkModelSignals.isRemoteOnly(null));
    }
}