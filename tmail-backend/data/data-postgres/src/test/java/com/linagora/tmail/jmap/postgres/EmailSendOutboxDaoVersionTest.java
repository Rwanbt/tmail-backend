package com.linagora.tmail.jmap.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EmailSendOutboxDaoVersionTest {
    @Test
    void rejectsNegativeAndOverflowingVersions() {
        assertThat(EmailSendOutboxDao.invalidVersion(-1L)).isTrue();
        assertThat(EmailSendOutboxDao.invalidVersion(Long.MIN_VALUE)).isTrue();
        assertThat(EmailSendOutboxDao.invalidVersion(Long.MAX_VALUE)).isTrue();
        assertThat(EmailSendOutboxDao.invalidVersion(0L)).isFalse();
        assertThat(EmailSendOutboxDao.invalidVersion(Long.MAX_VALUE - 1L)).isFalse();
    }
}
