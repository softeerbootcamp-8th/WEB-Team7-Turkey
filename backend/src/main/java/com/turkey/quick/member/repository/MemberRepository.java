package com.turkey.quick.member.repository;

import com.turkey.quick.member.domain.Member;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberRepository extends JpaRepository<Member, Long> {

    boolean existsByPhoneNumber(String phoneNumber);

    boolean existsByLoginId(String loginId);
}
