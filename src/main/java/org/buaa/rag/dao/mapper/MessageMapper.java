package org.buaa.rag.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.buaa.rag.dao.entity.MessageDO;

import java.util.List;
import java.util.Optional;

public interface MessageMapper extends BaseMapper<MessageDO> {

    List<MessageDO> findTop20BySessionIdOrderByCreatedAtAsc(String sessionId);

    Optional<MessageDO> findTop1ByUserIdOrderByCreatedAtDesc(String userId);
}
