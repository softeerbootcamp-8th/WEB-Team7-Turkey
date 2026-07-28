package com.turkey.quick.customer.service;

import com.turkey.quick.common.exception.BusinessException;
import com.turkey.quick.customer.dto.CustomerSignupRequest;
import com.turkey.quick.member.domain.Member;
import com.turkey.quick.member.domain.MemberRole;
import com.turkey.quick.member.domain.MemberTermAgreement;
import com.turkey.quick.member.domain.Term;
import com.turkey.quick.member.domain.TermTargetRole;
import com.turkey.quick.member.domain.VerificationPurpose;
import com.turkey.quick.member.repository.MemberRepository;
import com.turkey.quick.member.repository.MemberTermAgreementRepository;
import com.turkey.quick.member.repository.TermRepository;
import com.turkey.quick.member.service.VerificationCodeStore;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 고객 회원가입. 처리 흐름(이슈 #25)은 형식 검증 → 중복 확인 → 휴대전화 인증 확인 → 비밀번호 해시
 * → 계정·약관 동의 저장 순이며, 마지막 저장 단계는 하나의 트랜잭션으로 묶는다.
 *
 * customer 전용 프로필 테이블은 만들지 않는다 — 이번 이슈의 입력값은 전부 Member로 충분히
 * 표현되고, ERD의 customer 테이블은 실제로 고객 전용 컬럼이 필요해지는 시점에 추가하기로
 * 결정했다(#25 계약 확정, 사람 확인).
 */
@Service
@RequiredArgsConstructor
public class CustomerSignupService {

    private final MemberRepository memberRepository;
    private final TermRepository termRepository;
    private final MemberTermAgreementRepository memberTermAgreementRepository;
    private final VerificationCodeStore verificationCodeStore;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Transactional
    public CustomerSignupResult signup(CustomerSignupRequest request) {
        if (!request.password().equals(request.passwordConfirm())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "비밀번호가 일치하지 않습니다.");
        }

        String loginId = request.loginId();
        String phoneNumber = request.phoneNumber().replace("-", "");

        // DB unique 제약(uk_member_login_id, uk_member_phone_number)이 최종 방어선이다.
        // 여기서는 흔한 경우를 빠르게 걸러 불필요한 저장 시도·해시 연산을 줄이는 용도다.
        if (memberRepository.existsByLoginId(loginId)) {
            throw new BusinessException(HttpStatus.CONFLICT, "이미 사용 중인 아이디입니다.");
        }
        if (memberRepository.existsByPhoneNumber(phoneNumber)) {
            throw new BusinessException(HttpStatus.CONFLICT, "이미 가입된 휴대전화 번호입니다.");
        }

        // 결정적 검증(약관)을 먼저 끝내고, 되돌릴 수 없는 토큰 소비(Redis GETDEL)는 마지막에 한다.
        // 순서가 반대면 약관 미동의로 400을 받은 사용자가 약관만 고쳐 재시도할 때 토큰이 이미
        // 사라져 있어 인증부터 다시 받아야 한다.
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<Term> effectiveTerms = termRepository.findByActiveTrueAndTargetRoleIn(
                        List.of(TermTargetRole.COMMON, TermTargetRole.CUSTOMER))
                .stream()
                .filter(term -> term.isEffectiveAt(now))
                .toList();
        List<Term> agreedTerms = resolveAgreedTerms(effectiveTerms, request.agreedTermIds());

        verifyPhoneVerification(request.phoneVerificationToken(), phoneNumber);

        Member member = Member.create(
                loginId, passwordEncoder.encode(request.password()), request.name(), phoneNumber, MemberRole.CUSTOMER);
        try {
            memberRepository.save(member);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(HttpStatus.CONFLICT, "이미 사용 중인 아이디 또는 휴대전화 번호입니다.");
        }

        List<MemberTermAgreement> agreements = agreedTerms.stream()
                .map(term -> MemberTermAgreement.create(member, term, true))
                .toList();
        memberTermAgreementRepository.saveAll(agreements);

        return CustomerSignupResult.from(member);
    }

    private void verifyPhoneVerification(String token, String phoneNumber) {
        String value = verificationCodeStore.consumeVerifiedToken(token);
        if (value == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "휴대전화 인증이 필요하거나 인증이 만료되었습니다.");
        }

        String[] parts = value.split(":", 2);
        VerificationPurpose purpose = VerificationPurpose.valueOf(parts[0]);
        String verifiedPhoneNumber = parts[1];

        if (purpose != VerificationPurpose.SIGNUP) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "회원가입 목적으로 인증된 토큰이 아닙니다.");
        }
        if (!verifiedPhoneNumber.equals(phoneNumber)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "인증된 휴대전화 번호와 일치하지 않습니다.");
        }
    }

    private List<Term> resolveAgreedTerms(List<Term> activeTerms, List<Long> agreedTermIds) {
        Set<Long> agreedIds = Set.copyOf(agreedTermIds);

        boolean hasUnknownTerm = agreedIds.stream()
                .anyMatch(id -> activeTerms.stream().noneMatch(term -> term.getId().equals(id)));
        if (hasUnknownTerm) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "유효하지 않은 약관입니다.");
        }

        boolean missingRequired = activeTerms.stream()
                .filter(Term::isRequired)
                .anyMatch(term -> !agreedIds.contains(term.getId()));
        if (missingRequired) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "필수 약관에 모두 동의해야 합니다.");
        }

        return activeTerms.stream().filter(term -> agreedIds.contains(term.getId())).collect(Collectors.toList());
    }
}
