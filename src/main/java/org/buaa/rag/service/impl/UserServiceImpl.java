package org.buaa.rag.service.impl;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.buaa.rag.common.convention.exception.ServiceException;
import org.buaa.rag.dao.entity.UserDO;
import org.buaa.rag.dao.mapper.UserMapper;
import org.buaa.rag.service.UserService;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

/**
 * 用户接口实现层
 */
@Service
@RequiredArgsConstructor
public class UserServiceImpl extends ServiceImpl<UserMapper, UserDO> implements UserService {

    private final JavaMailSender mailSender;

    private final ExcelUtils excelUtils;

    @Value("${spring.mail.username}")
    private String from;

    @Override
    public UserRespDTO getUserByUsername(String username) {
        LambdaQueryWrapper<UserDO> queryWrapper = Wrappers.lambdaQuery(UserDO.class)
                .eq(UserDO::getUsername, username);
        UserDO userDO = baseMapper.selectOne(queryWrapper);
        if (userDO == null) {
            throw new ServiceException(UserErrorCodeEnum.USER_NULL);
        }
        UserRespDTO result = new UserRespDTO();
        BeanUtils.copyProperties(userDO, result);
        result.setLikeCount(redisCount.hGet(USER_COUNT_KEY + result.getId(), "like"));
        result.setCollectCount(redisCount.hGet(USER_COUNT_KEY + result.getId(), "collect"));
        result.setUsefulCount(redisCount.hGet(USER_COUNT_KEY + result.getId(), "useful"));
        return result;
    }

    @Override
    public Boolean hasUsername(String username) {
        LambdaQueryWrapper<UserDO> queryWrapper = Wrappers.lambdaQuery(UserDO.class)
                .eq(UserDO::getUsername, username);
        UserDO userDO = baseMapper.selectOne(queryWrapper);
        return userDO != null;
    }

    @Override
    public Boolean hasMail(String mail){
        LambdaQueryWrapper<UserDO> queryWrapper = Wrappers.lambdaQuery(UserDO.class)
                .eq(UserDO::getMail, mail);
        UserDO userDO = baseMapper.selectOne(queryWrapper);
        return userDO != null;
    }
    @Override
    public Boolean sendCode(String mail) {
        SimpleMailMessage message = new SimpleMailMessage();
        String code = RandomGenerator.generateSixDigitCode();
        message.setFrom(from);
        message.setText(String.format(MailSendConstants.TEXT, code));
        message.setTo(mail);
        message.setSubject(MailSendConstants.SUBJECT);
        try {
            mailSender.send(message);
            String key = USER_REGISTER_CODE_KEY + mail.replace(EMAIL_SUFFIX,"");
            stringRedisTemplate.opsForValue().set(key, code, USER_REGISTER_CODE_EXPIRE_KEY, TimeUnit.MINUTES);
            return true;
        } catch (Exception e) {
            throw new ServiceException(MAIL_SEND_ERROR);
        }
    }

    @Override
    public void register(UserRegisterReqDTO requestParam) {
        String code = requestParam.getCode();
        String key = USER_REGISTER_CODE_KEY + requestParam.getMail().replace(EMAIL_SUFFIX,"");
        String cacheCode = stringRedisTemplate.opsForValue().get(key);
        if (!code.equals(cacheCode)) {
            throw new ClientException(USER_CODE_ERROR);
        }
        if (hasUsername(requestParam.getUsername())) {
            throw new ClientException(USER_NAME_EXIST);
        }
        if (hasMail(requestParam.getMail())) {
            throw new ClientException(USER_MAIL_EXIST);
        }
        RLock lock = redissonClient.getLock(USER_REGISTER_LOCK_KEY + requestParam.getUsername());
        if (!lock.tryLock()) {
            throw new ClientException(USER_NAME_EXIST);
        }
        try {
            UserDO userDO = BeanUtil.toBean(requestParam, UserDO.class);
            userDO.setSalt(UUID.randomUUID().toString().substring(0, 5));
            userDO.setPassword(DigestUtils.md5DigestAsHex((userDO.getPassword() + userDO.getSalt()).getBytes()));
            userDO.setUserType(excelUtils.isVolunteer(userDO.getMail().substring(0, 8)) ? VOLUNTEER.toString() : STUDENT.toString());
            userDO.setBanFlag(0);
            int inserted = baseMapper.insert(userDO);
            if (inserted < 1) {
                throw new ClientException(USER_SAVE_ERROR);
            }
            userDO = baseMapper.selectOne(Wrappers.lambdaQuery(UserDO.class)
                    .eq(UserDO::getUsername, requestParam.getUsername()));
            stringRedisTemplate.opsForValue().set(USER_INFO_KEY + requestParam.getUsername(), JSON.toJSONString(userDO));
            afterRegistry(SYSTEM_MESSAGE_ID, EntityTypeEnum.USER, 0L, userDO.getId());
        } catch (DuplicateKeyException ex) {
            throw new ClientException(USER_EXIST);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public UserLoginRespDTO login(UserLoginReqDTO requestParam, ServletRequest request) {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        Cookie[] cookies = httpRequest.getCookies();
        String captchaOwner = "";
        if (cookies != null) {
            captchaOwner = Arrays.stream(cookies)
                    .filter(cookie -> "CaptchaOwner".equals(cookie.getName()))
                    .findFirst()
                    .map(Cookie::getValue)
                    .orElse(null);
        }

        String code = stringRedisTemplate.opsForValue().get(USER_LOGIN_CAPTCHA_KEY + captchaOwner);
        if (StrUtil.isBlank(code) || !code.equalsIgnoreCase(requestParam.getCode())) {
            throw new ClientException(USER_LOGIN_CAPTCHA_ERROR);
        }

        if (!hasUsername(requestParam.getUsername())) {
            throw new ClientException(USER_NAME_NULL);
        }
        LambdaQueryWrapper<UserDO> queryWrapper = Wrappers.lambdaQuery(UserDO.class)
                .eq(UserDO::getUsername, requestParam.getUsername());
        UserDO userDO = baseMapper.selectOne(queryWrapper);

        String password = DigestUtils.md5DigestAsHex((requestParam.getPassword() + userDO.getSalt()).getBytes());
        if (!Objects.equals(userDO.getPassword(), password)) {
            throw new ClientException(USER_PASSWORD_ERROR);
        }

        if(userDO.getBanFlag()!=0){
            throw new ClientException(USER_BANNED);
        }

        /**
         * String
         * Key：user:login:username
         * Value: token标识
         */
        String hasLogin = stringRedisTemplate.opsForValue().get(USER_LOGIN_KEY + requestParam.getUsername());
        if (StrUtil.isNotEmpty(hasLogin)) {
            // throw new ClientException(USER_REPEATED_LOGIN);
            return new UserLoginRespDTO(hasLogin);
        }
        String uuid = UUID.randomUUID().toString();
        stringRedisTemplate.opsForValue().set(USER_LOGIN_KEY + requestParam.getUsername(), uuid, USER_LOGIN_EXPIRE_KEY, TimeUnit.DAYS);
        return new UserLoginRespDTO(uuid);
    }

    @Override
    public Boolean checkLogin(String username, String token) {
        String hasLogin = stringRedisTemplate.opsForValue().get(USER_LOGIN_KEY + username);
        if (StrUtil.isEmpty(hasLogin)) {
            return false;
        }
        return Objects.equals(hasLogin, token);
    }

    @Override
    public void logout(String username, String token) {
        if (checkLogin(username, token)) {
            stringRedisTemplate.delete(USER_LOGIN_KEY + username);
            return;
        }
        throw new ClientException(USER_TOKEN_NULL);
    }

    @Override
    public void update(UserUpdateReqDTO requestParam) {
        if (!Objects.equals(requestParam.getOldUsername(), UserContext.getUsername())) {
            throw new ClientException(USER_UPDATE_ERROR);
        }
        if (!requestParam.getOldUsername().equals(requestParam.getNewUsername()) && hasUsername(requestParam.getNewUsername())) {
            throw new ClientException(USER_NAME_EXIST);
        }
        String password = DigestUtils.md5DigestAsHex((requestParam.getPassword() + UserContext.getSalt()).getBytes());
        UserDO userDO = UserDO.builder()
                .username(requestParam.getNewUsername())
                .password(password)
                .avatar(requestParam.getAvatar())
                .phone(requestParam.getPhone())
                .introduction(requestParam.getIntroduction())
                .build();
        LambdaUpdateWrapper<UserDO> updateWrapper = Wrappers.lambdaUpdate(UserDO.class)
                .eq(UserDO::getUsername, requestParam.getOldUsername());
        baseMapper.update(userDO, updateWrapper);
        /**
         * 更新redis缓存
         */
        if (!requestParam.getOldUsername().equals(requestParam.getNewUsername())) {
            stringRedisTemplate.delete(USER_INFO_KEY + requestParam.getOldUsername());
            stringRedisTemplate.opsForValue().set(USER_LOGIN_KEY + requestParam.getNewUsername(), UserContext.getToken(), USER_LOGIN_EXPIRE_KEY, TimeUnit.DAYS);
        }
        UserDO newUserDO = baseMapper.selectOne(Wrappers.lambdaQuery(UserDO.class)
                .eq(UserDO::getUsername, requestParam.getNewUsername()));
        stringRedisTemplate.opsForValue().set(USER_INFO_KEY + requestParam.getNewUsername(), JSON.toJSONString(newUserDO));
    }

    @Override
    public List<UserActivityRankRespDTO> activityRank() {
        Set<String> activeUsers = stringRedisTemplate.opsForZSet().reverseRange(ACTIVITY_SCORE_KEY, 0, 9);
        if (activeUsers != null && !activeUsers.isEmpty()) {
            return activeUsers.stream().map(userId -> {
                UserDO userDO = baseMapper.selectById(userId);
                UserActivityRankRespDTO result = new UserActivityRankRespDTO();
                BeanUtils.copyProperties(userDO, result);
                Double score = stringRedisTemplate.opsForZSet().score(ACTIVITY_SCORE_KEY, userId);
                result.setActivity(score == null ? 0 : score.intValue());
                return result;
            }).toList();
        }
        return Collections.emptyList();
    }

    @Override
    public Integer activityScore() {
        Double score = stringRedisTemplate.opsForZSet().score(ACTIVITY_SCORE_KEY, UserContext.getUserId().toString());
        return score == null ? 0 : score.intValue();
    }

    public void afterRegistry(Long userId, EntityTypeEnum entityType, Long entityId, Long entityUserId) {
        HashMap<String, Object> data = new HashMap<>();
        data.put("content", "欢迎注册源智答小程序");
        MqEvent event = MqEvent.builder()
                .messageType(MessageTypeEnum.SYSTEM)
                .entityType(entityType)
                .userId(userId)
                .entityId(entityId)
                .entityUserId(entityUserId)
                .generateId(0L)
                .data(data)
                .build();
        producer.messageSend(event);
    }

    @Override
    public Boolean forgetUsername(String mail) {
        SimpleMailMessage message = new SimpleMailMessage();
        LambdaQueryWrapper<UserDO> queryWrapper = Wrappers.lambdaQuery(UserDO.class)
                //.select(UserDO::getUsername)
                .eq(UserDO::getMail, mail);
        UserDO user = baseMapper.selectOne(queryWrapper);
        if (user == null) {
            throw new ServiceException(USER_NULL);
        }
        String username = user.getUsername();
        message.setFrom(from);
        message.setText(String.format(MailSendConstants.FORGET_TEXT, username));
        message.setTo(mail);
        message.setSubject(MailSendConstants.SUBJECT_FORGET_USERNAME);
        try {
            mailSender.send(message);
            return true;
        } catch (Exception e) {
            throw new ServiceException(MAIL_SEND_ERROR);
        }
    }

    @Override
    public Boolean sendResetPasswordCode(String mail) {
        if (!hasMail(mail)) {
            throw new ClientException(UserErrorCodeEnum.USER_NULL);
        }
        SimpleMailMessage message = new SimpleMailMessage();
        String code = RandomGenerator.generateSixDigitCode();
        message.setFrom(from);
        message.setText(String.format(MailSendConstants.RESET_TEXT, code));
        message.setTo(mail);
        message.setSubject(MailSendConstants.SUBJECT);
        try {
            mailSender.send(message);
            String key = USER_RESET_CODE_KEY + mail.replace(EMAIL_SUFFIX,"");
            stringRedisTemplate.opsForValue().set(key, code, USER_RESET_CODE_EXPIRE_KEY, TimeUnit.MINUTES);
            return true;
        } catch (Exception e) {
            throw new ServiceException(MAIL_SEND_ERROR);
        }
    }

    @Override
    public Boolean resetPassword(UserResetPwdReqDTO requestParam) {
        // 1. 查用户
        if(!hasUsername(requestParam.getUsername())){
            throw new ClientException(UserErrorCodeEnum.USER_NULL);
        }
        UserDO userDO = baseMapper.selectOne(
                Wrappers.lambdaQuery(UserDO.class).eq(UserDO::getUsername, requestParam.getUsername()));
        if (userDO == null) {
            throw new ClientException(UserErrorCodeEnum.USER_NULL);
        }
        // 2. 校验邮箱验证码（与发送时相同的 Key 规则）
        String key = USER_RESET_CODE_KEY + userDO.getMail().replace(EMAIL_SUFFIX, "");
        String cacheCode = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(cacheCode) || !cacheCode.equals(requestParam.getCode())) {
            throw new ClientException(USER_CODE_ERROR);
        }

        // 3. 计算新密码哈希（MD5(明文 + 用户salt)）
        String hashed = DigestUtils.md5DigestAsHex((requestParam.getNewPassword() + userDO.getSalt()).getBytes());

        // 4. 覆盖保存密码
        UserDO update = UserDO.builder().password(hashed).build();
        baseMapper.update(update, Wrappers.lambdaUpdate(UserDO.class)
                .eq(UserDO::getUsername, requestParam.getUsername()));

        // 5. 清理验证码与登录态
        stringRedisTemplate.delete(key);
        stringRedisTemplate.delete(USER_LOGIN_KEY + requestParam.getUsername());

        // 6. 刷新用户缓存
        UserDO fresh = baseMapper.selectOne(
                Wrappers.lambdaQuery(UserDO.class).eq(UserDO::getUsername, requestParam.getUsername()));
        stringRedisTemplate.opsForValue().set(USER_INFO_KEY + requestParam.getUsername(), JSON.toJSONString(fresh));
        return true;
    }
}
