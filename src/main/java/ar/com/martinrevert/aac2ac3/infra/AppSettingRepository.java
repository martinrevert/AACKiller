package ar.com.martinrevert.aac2ac3.infra;

import ar.com.martinrevert.aac2ac3.model.AppSetting;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AppSettingRepository extends JpaRepository<AppSetting, Long> {
    Optional<AppSetting> findBySettingKey(String settingKey);
}
