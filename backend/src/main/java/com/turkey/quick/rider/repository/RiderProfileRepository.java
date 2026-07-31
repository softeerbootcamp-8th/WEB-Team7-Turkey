package com.turkey.quick.rider.repository;

import com.turkey.quick.rider.domain.RiderProfile;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RiderProfileRepository extends JpaRepository<RiderProfile, Long> {
}
