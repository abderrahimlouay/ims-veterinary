package com.imsvet.ims.settings.infrastructure;

import com.imsvet.ims.settings.domain.AppSetting;
import java.util.Optional;
import org.springframework.data.repository.Repository;

public interface AppSettingRepository extends Repository<AppSetting, String> {

    Optional<AppSetting> findById(String key);
}
