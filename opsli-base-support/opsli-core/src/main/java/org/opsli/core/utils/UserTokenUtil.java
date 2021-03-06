/**
 * Copyright 2020 OPSLI 快速开发平台 https://www.opsli.com
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.opsli.core.utils;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.date.DateTime;
import cn.hutool.core.date.DateUnit;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import com.google.common.collect.Maps;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.shiro.crypto.hash.Md5Hash;
import org.opsli.api.base.result.ResultVo;
import org.opsli.api.wrapper.system.user.UserModel;
import org.opsli.common.constants.SignConstants;
import org.opsli.common.constants.TokenConstants;
import org.opsli.common.exception.ServiceException;
import org.opsli.common.exception.TokenException;
import org.opsli.common.utils.Props;
import org.opsli.core.msg.TokenMsg;
import org.opsli.plugins.redis.RedisPlugin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.servlet.http.HttpServletRequest;
import java.util.Date;
import java.util.Map;

import static org.opsli.common.constants.OrderConstants.UTIL_ORDER;

/**
 * User Token Util
 *
 * @author parker
 */

@Slf4j
@Order(UTIL_ORDER)
@Component
@AutoConfigureAfter({RedisPlugin.class})
@Lazy(false)
public class UserTokenUtil {

    /** token 缓存名 */
    public static final String TOKEN_NAME = TokenConstants.ACCESS_TOKEN;
    /** 缓存前缀 */
    private static final String PREFIX = "opsli:ticket:";
    /** 账号失败次数 */
    public static final String ACCOUNT_SLIP_COUNT_PREFIX = "opsli:account:slip:count:";
    /** 账号失败锁定KEY */
    public static final String ACCOUNT_SLIP_LOCK_PREFIX = "opsli:account:slip:lock:";
    /** 账号失败阈值 */
    public static final int ACCOUNT_SLIP_COUNT;
    /** 账号失败N次后弹出验证码 */
    public static final int ACCOUNT_SLIP_VERIFY_COUNT;
    /** 账号锁定时间 */
    public static final int ACCOUNT_SLIP_LOCK_SPEED;

    /** Redis插件 */
    private static RedisPlugin redisPlugin;



    static {
        Props props = new Props("application.yaml");
        ACCOUNT_SLIP_COUNT = props.getInt("opsli.login.slip-count", 5);
        ACCOUNT_SLIP_VERIFY_COUNT = props.getInt("opsli.login.slip-verify-count", 3);
        ACCOUNT_SLIP_LOCK_SPEED = props.getInt("opsli.login.slip-lock-speed", 300);
    }

    /**
     * 根据 user 创建Token
     * @param user
     * @return
     */
    public static ResultVo<Map<String,Object>> createToken(UserModel user) {
        if (user == null) {
            // 生成Token失败
            return ResultVo.error(TokenMsg.EXCEPTION_TOKEN_CREATE_ERROR.getMessage());
        }

        Map<String,Object> map = Maps.newHashMapWithExpectedSize(2);
        try {

            // 生效时间
            int expire = Integer.parseInt(
                    String.valueOf(JwtUtil.EXPIRE)
            );

            // 生成 Token 包含 username userId timestamp
            String signToken = JwtUtil.sign(user.getUsername(), user.getId());

            // 生成MD5 16进制码 用于缩减存储
            String signTokenHex = new Md5Hash(signToken).toHex();

            // 获得当前时间戳时间
            long timestamp = Long.parseLong(JwtUtil.getClaim(signToken, SignConstants.TIMESTAMP));
            DateTime currDate = DateUtil.date(timestamp);

            // 获得失效偏移量时间
            DateTime dateTime = DateUtil.offsetMillisecond(currDate, expire);
            long endTimestamp = dateTime.getTime();

            // token 缓存真实失效时间 建议大于 最终时间 -- 多加了20分钟的失效时间
            // 在redis存一份 token 是为了防止 认为造假
            boolean tokenFlag = redisPlugin.put(PREFIX + signTokenHex, endTimestamp, expire + 20);
            if(tokenFlag){
                map.put("token", signToken);
                map.put("expire", endTimestamp);
                return ResultVo.success(map);
            }

            // 生成Token失败
            return ResultVo.error(TokenMsg.EXCEPTION_TOKEN_CREATE_ERROR.getMessage());

        }catch (Exception e){
            log.error(e.getMessage() , e);
            return ResultVo.error(e.getMessage());
        }
    }

    /**
     * 根据 Token 获得用户ID
     * @param token
     * @return
     */
    public static String getUserIdByToken(String token) {
        if(StringUtils.isEmpty(token)) return null;
        String userId = "";
        try {
            userId = JwtUtil.getClaim(token, SignConstants.USER_ID);
        }catch (Exception e){}
        return userId;
    }

    /**
     * 根据 Token 获得 username
     * @param token
     * @return
     */
    public static String getUserNameByToken(String token) {
        if(StringUtils.isEmpty(token)) return null;
        String username = "";
        try {
            username = JwtUtil.getClaim(token, SignConstants.ACCOUNT);
        }catch (Exception ignored){}
        return username;
    }



    /**
     * 退出登陆
     * @param token
     */
    public static void logout(String token) {
        if(StringUtils.isEmpty(token)) return;
        try {
            // 生成MD5 16进制码 用于缩减存储
            String signTokenHex = new Md5Hash(token).toHex();

            redisPlugin.del(PREFIX + signTokenHex);

            // 删除相关信息
            String userId = getUserIdByToken(token);
            UserModel user = UserUtil.getUser(userId);
            if(user != null){
                UserUtil.refreshUser(user);
                UserUtil.refreshUserRoles(user.getId());
                UserUtil.refreshUserAllPerms(user.getId());
                UserUtil.refreshUserMenus(user.getId());
            }

        }catch (Exception ignored){}
    }

    /**
     * 验证 token
     * @param token
     */
    public static boolean verify(String token) {
        if(StringUtils.isEmpty(token)) return false;

        try {
            // 1. 校验是否是有效的 token
            boolean tokenVerify = JwtUtil.verify(token);
            if(!tokenVerify){
                return false;
            }

            // 2. 校验当前缓存中token是否失效
            // 生成MD5 16进制码 用于缩减存储
            String signTokenHex = new Md5Hash(token).toHex();
            Long  endTimestamp = (Long) redisPlugin.get(PREFIX + signTokenHex);
            if(endTimestamp == null){
                return false;
            }

            // JWT 自带过期校验 无需多做处理

        } catch (Exception e){
            return false;
        }
        return true;
    }

    // ============================ 锁账号 操作

    /**
     * 验证锁定账号
     * @param username
     */
    public static void verifyLockAccount(String username){
        // 判断账号是否临时锁定
        Long loseTimeMillis = (Long) redisPlugin.get(ACCOUNT_SLIP_LOCK_PREFIX + username);
        if(loseTimeMillis != null){
            Date currDate = new Date();
            DateTime loseDate = DateUtil.date(loseTimeMillis);
            // 偏移5分钟
            DateTime currLoseDate = DateUtil.offsetSecond(loseDate, ACCOUNT_SLIP_LOCK_SPEED);

            // 计算失效剩余时间( 分 )
            long betweenM = DateUtil.between(currLoseDate, currDate, DateUnit.MINUTE);
            if(betweenM > 0){
                String msg = StrUtil.format(TokenMsg.EXCEPTION_LOGIN_ACCOUNT_LOCK.getMessage()
                        ,betweenM + "分钟");
                throw new TokenException(TokenMsg.EXCEPTION_LOGIN_ACCOUNT_LOCK.getCode(), msg);
            }else{
                // 计算失效剩余时间( 秒 )
                long betweenS = DateUtil.between(currLoseDate, currDate, DateUnit.SECOND);
                String msg = StrUtil.format(TokenMsg.EXCEPTION_LOGIN_ACCOUNT_LOCK.getMessage()
                        ,betweenS + "秒");
                throw new TokenException(TokenMsg.EXCEPTION_LOGIN_ACCOUNT_LOCK.getCode(), msg);
            }
        }
    }

    /**
     * 锁定账号
     * @param username
     */
    public static ResultVo<?> lockAccount(String username){
        // 如果失败次数 超过阈值 则锁定账号
        Long slipNum = redisPlugin.increment(ACCOUNT_SLIP_COUNT_PREFIX + username);
        if (slipNum != null){
            // 设置失效时间为 5分钟
            redisPlugin.expire(ACCOUNT_SLIP_COUNT_PREFIX + username, ACCOUNT_SLIP_LOCK_SPEED);

            // 如果确认 都失败 则存入临时缓存
            if(slipNum >= ACCOUNT_SLIP_COUNT){
                long currentTimeMillis = System.currentTimeMillis();
                // 存入Redis
                redisPlugin.put(ACCOUNT_SLIP_LOCK_PREFIX + username,
                        currentTimeMillis, ACCOUNT_SLIP_LOCK_SPEED);
            }
        }

        Map<String,Boolean> flagMap = Maps.newHashMap();
        flagMap.put("izVerify", false);
        if(slipNum != null && slipNum >= ACCOUNT_SLIP_VERIFY_COUNT){
            flagMap.put("izVerify", true);
        }
        return ResultVo.error(TokenMsg.EXCEPTION_LOGIN_ACCOUNT_NO.getCode(),
                TokenMsg.EXCEPTION_LOGIN_ACCOUNT_NO.getMessage(),
                flagMap
                );
    }

    /**
     * 获得当前失败次数
     * @param username
     */
    public static long getSlipCount(String username){
        long count = 0L;
        Object obj = redisPlugin.get(ACCOUNT_SLIP_COUNT_PREFIX + username);
        if(obj != null){
            try {
                count = Convert.convert(Long.class, obj);
            }catch (Exception ignored){}
        }
        return count;
    }


    /**
     * 清除锁定账号
     * @param username
     */
    public static void clearLockAccount(String username){
        // 删除失败次数记录
        redisPlugin.del(ACCOUNT_SLIP_COUNT_PREFIX + username);
        // 删除失败次数记录
        redisPlugin.del(ACCOUNT_SLIP_LOCK_PREFIX + username);
    }


    // ==========================

    /**
     * 获取请求的token
     */
    public static String getRequestToken(HttpServletRequest httpRequest){

        //从header中获取token
        String token = httpRequest.getHeader(TOKEN_NAME);

        //如果header中不存在token，则从参数中获取token
        if(StringUtils.isBlank(token)){
            token = httpRequest.getParameter(TOKEN_NAME);
        }

        return token;
    }

    @Autowired
    public  void setRedisPlugin(RedisPlugin redisPlugin) {
        UserTokenUtil.redisPlugin = redisPlugin;
    }

}
