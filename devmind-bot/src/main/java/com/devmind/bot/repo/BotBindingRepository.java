package com.devmind.bot.repo;

import com.devmind.bot.model.BotBindingEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BotBindingRepository extends JpaRepository<BotBindingEntity, Long> {

    Optional<BotBindingEntity> findByChannelAndExternalChatId(String channel, String externalChatId);
}
