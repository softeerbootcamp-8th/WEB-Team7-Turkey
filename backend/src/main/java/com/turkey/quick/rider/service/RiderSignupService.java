package com.turkey.quick.rider.service;

import com.turkey.quick.common.exception.BusinessException;
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
import com.turkey.quick.payment.domain.PointWallet;
import com.turkey.quick.payment.repository.PointWalletRepository;
import com.turkey.quick.rider.domain.RiderProfile;
import com.turkey.quick.rider.dto.RiderSignupRequest;
import com.turkey.quick.rider.repository.RiderProfileRepository;
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
 * 라이더 회원가입(이슈 #48). {@code CustomerSignupService}(#25)와 처리 흐름·판단이 동일하고,
 * 역할이 RIDER라는 것과 라이더 프로필(RiderProfile, 초기 상태 UNAVAILABLE)을 함께 만든다는
 * 점만 다르다.
 */
@Service
@RequiredArgsConstructor
public class RiderSignupService {

    private final MemberRepository memberRepository;
    private final RiderProfileRepository riderProfileRepository;
    private final TermRepository termRepository;
    private final MemberTermAgreementRepository memberTermAgreementRepository;
    private final PointWalletRepository pointWalletRepository;
    private final VerificationCodeStore verificationCodeStore;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Transactional
    public RiderSignupResult signup(RiderSignupRequest request) {
        if (!request.password().equals(request.passwordConfirm())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "비밀번호가 일치하지 않습니다.");
        }

        String loginId = request.loginId();
        String phoneNumber = request.phoneNumber().replace("-", "");

        if (memberRepository.existsByLoginId(loginId)) {
            throw new BusinessException(HttpStatus.CONFLICT, "이미 사용 중인 아이디입니다.");
        }
        if (memberRepository.existsByPhoneNumber(phoneNumber)) {
            throw new BusinessException(HttpStatus.CONFLICT, "이미 가입된 휴대전화 번호입니다.");
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<Term> effectiveTerms = termRepository.findByActiveTrueAndTargetRoleIn(
                        List.of(TermTargetRole.COMMON, TermTargetRole.RIDER))
                .stream()
                .filter(term -> term.isEffectiveAt(now))
                .toList();
        List<Term> agreedTerms = resolveAgreedTerms(effectiveTerms, request.agreedTermIds());

        verifyPhoneVerification(request.phoneVerificationToken(), phoneNumber);

        Member member = Member.create(
                loginId, passwordEncoder.encode(request.password()), request.name(), phoneNumber, MemberRole.RIDER);
        try {
            memberRepository.save(member);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(HttpStatus.CONFLICT, "이미 사용 중인 아이디 또는 휴대전화 번호입니다.");
        }

        riderProfileRepository.save(RiderProfile.create(member));
        pointWalletRepository.save(PointWallet.create(member));

        List<MemberTermAgreement> agreements = agreedTerms.stream()
                .map(term -> MemberTermAgreement.create(member, term, true))
                .toList();
        memberTermAgreementRepository.saveAll(agreements);

        return RiderSignupResult.from(member);
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
