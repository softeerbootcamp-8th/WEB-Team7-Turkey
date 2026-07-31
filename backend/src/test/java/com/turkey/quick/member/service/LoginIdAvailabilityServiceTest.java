package com.turkey.quick.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.turkey.quick.member.repository.MemberRepository;
import org.junit.jupiter.api.Test;

class LoginIdAvailabilityServiceTest {

    @Test
    void 사용중이지_않은_아이디는_사용_가능하다() {
        MemberRepository memberRepository = mock(MemberRepository.class);
        when(memberRepository.existsByLoginId("new_user")).thenReturn(false);
        LoginIdAvailabilityService service = new LoginIdAvailabilityService(memberRepository);

        LoginIdAvailabilityResult result = service.check("new_user");

        assertThat(result.available()).isTrue();
        assertThat(result.reason()).isNull();
    }

    @Test
    void 이미_사용중인_아이디는_사용_불가하고_사유를_반환한다() {
        MemberRepository memberRepository = mock(MemberRepository.class);
        when(memberRepository.existsByLoginId("taken_id")).thenReturn(true);
        LoginIdAvailabilityService service = new LoginIdAvailabilityService(memberRepository);

        LoginIdAvailabilityResult result = service.check("taken_id");

        assertThat(result.available()).isFalse();
        assertThat(result.reason()).isNotBlank();
    }
}
