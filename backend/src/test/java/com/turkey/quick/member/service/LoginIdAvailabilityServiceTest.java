package com.turkey.quick.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.turkey.quick.member.repository.MemberRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LoginIdAvailabilityServiceTest {

    @Test
    @DisplayName("사용중이지 않은 아이디는 사용 가능하다")
    void shouldReportUnusedLoginIdAsAvailable() {
        MemberRepository memberRepository = mock(MemberRepository.class);
        when(memberRepository.existsByLoginId("new_user")).thenReturn(false);
        LoginIdAvailabilityService service = new LoginIdAvailabilityService(memberRepository);

        LoginIdAvailabilityResult result = service.check("new_user");

        assertThat(result.available()).isTrue();
        assertThat(result.reason()).isNull();
    }

    @Test
    @DisplayName("이미 사용중인 아이디는 사용 불가하고 사유를 반환한다")
    void shouldReportUsedLoginIdAsUnavailableWithReason() {
        MemberRepository memberRepository = mock(MemberRepository.class);
        when(memberRepository.existsByLoginId("taken_id")).thenReturn(true);
        LoginIdAvailabilityService service = new LoginIdAvailabilityService(memberRepository);

        LoginIdAvailabilityResult result = service.check("taken_id");

        assertThat(result.available()).isFalse();
        assertThat(result.reason()).isNotBlank();
    }
}
