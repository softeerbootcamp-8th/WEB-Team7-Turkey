package com.turkey.quick.member.repository;

import com.turkey.quick.member.domain.Term;
import com.turkey.quick.member.domain.TermTargetRole;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TermRepository extends JpaRepository<Term, Long> {

    List<Term> findByActiveTrueAndTargetRoleIn(Collection<TermTargetRole> targetRoles);
}
