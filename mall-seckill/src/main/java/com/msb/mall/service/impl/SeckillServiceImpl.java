package com.msb.mall.service.impl;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.fastjson.JSON;
import com.msb.common.constant.OrderConstant;
import com.msb.common.constant.SeckillConstant;
import com.msb.common.dto.SeckillOrderDto;
import com.msb.common.utils.R;
import com.msb.common.vo.MemberVO;
import com.msb.mall.dto.SeckillSkuRedisDto;
import com.msb.mall.feign.CouponFeignService;
import com.msb.mall.feign.ProductFeignService;
import com.msb.mall.interceptor.AuthInterceptor;
import com.msb.mall.service.SeckillService;
import com.msb.mall.vo.SeckillSessionEntity;
import com.msb.mall.vo.SeckillSkuRelationEntity;
import com.msb.mall.vo.SkuInfoVo;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.skywalking.apm.toolkit.trace.Trace;
import org.redisson.api.RSemaphore;
import org.redisson.api.RedissonClient;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.BoundHashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class SeckillServiceImpl implements SeckillService {

    @Autowired
    CouponFeignService couponFeignService;

    @Autowired
    ProductFeignService productFeignService;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    RedissonClient redissonClient;

    @Autowired
    RocketMQTemplate rocketMQTemplate;

    @Trace
    @Override
    public void uploadSeckillSku3Days() {
        // 1. ??????OpenFegin ????????????Coupon????????????????????????????????????????????????????????????
        R r = couponFeignService.getLates3DaysSession();
        if(r.getCode() == 0){
            // ????????????????????????
            String json = (String) r.get("data");
            List<SeckillSessionEntity> seckillSessionEntities = JSON.parseArray(json,SeckillSessionEntity.class);
            // 2. ????????????  Redis????????????
            // ????????????
            //  2.1 ?????????????????????SKU????????????
            saveSessionInfos(seckillSessionEntities);
            // 2.2  ?????????????????????????????????
            saveSessionSkuInfos(seckillSessionEntities);

        }
    }

    public List<SeckillSkuRedisDto> blockHandler(BlockException blockException){
        log.error("??????????????? blockHandler ?????? ....{}",blockException.getMessage());
        return null;
    }

    /**
     * ?????????????????????????????????????????????????????????SKU??????
     * @return
     */
    @SentinelResource(value = "currentSeckillSkusResources",blockHandler = "blockHandler")
    @Override
    public List<SeckillSkuRedisDto> getCurrentSeckillSkus() {
        // 1.????????????????????????????????????????????????
        long time = new Date().getTime();

        try (Entry entry = SphU.entry("getCurrentSeckillSkusResources")) {
            // ????????????????????????
            // ???Redis??????????????????????????????
            Set<String> keys = redisTemplate.keys(SeckillConstant.SESSION_CHACE_PREFIX + "*");
            for (String key : keys) {
                //seckill:sessions1656468000000_1656469800000
                String replace = key.replace(SeckillConstant.SESSION_CHACE_PREFIX, "");
                // 1656468000000_1656469800000
                String[] s = replace.split("_");
                Long start = Long.parseLong(s[0]); // ?????????????????????
                Long end = Long.parseLong(s[1]); // ?????????????????????
                if(time > start && time < end){
                    // ????????????????????????????????????????????????????????????
                    // ???????????????SKU???ID  2_9
                    List<String> range = redisTemplate.opsForList().range(key, -100, 100);
                    BoundHashOperations<String, String, String> ops = redisTemplate.boundHashOps(SeckillConstant.SKU_CHACE_PREFIX);
                    List<String> list = ops.multiGet(range);
                    if(list != null && list.size() > 0){
                        List<SeckillSkuRedisDto> collect = list.stream().map(item -> {
                            SeckillSkuRedisDto seckillSkuRedisDto = JSON.parseObject(item, SeckillSkuRedisDto.class);
                            return seckillSkuRedisDto;
                        }).collect(Collectors.toList());
                        return collect;
                    }
                }
            }
        } catch (BlockException ex) {
            // ??????????????????????????????????????????
            log.error("getCurrentSeckillSkusResources??????????????????...");
            // ????????????????????????????????????
        }


        return null;
    }

    /**
     * ??????SKUID?????????????????????????????????
     * @param skuId
     * @return
     */
    @Override
    public SeckillSkuRedisDto getSeckillSessionBySkuId(Long skuId) {
        // 1.??????????????????????????????????????????sku??????
        BoundHashOperations<String, String, String> ops = redisTemplate.boundHashOps(SeckillConstant.SKU_CHACE_PREFIX);
        Set<String> keys = ops.keys();
        if(keys != null && keys.size() > 0){
            String regx = "\\d_"+ skuId; // 2_1
            for (String key : keys) {
                boolean matches = Pattern.matches(regx, key);
                if(matches){
                    // ????????????????????????SKU?????????
                    String json = ops.get(key);
                    SeckillSkuRedisDto dto = JSON.parseObject(json, SeckillSkuRedisDto.class);
                    return dto;
                }
            }
        }
        return null;
    }

    /**
     * ??????????????????
     * @param killId
     * @param code
     * @param num
     * @return
     */
    @Override
    public String kill(String killId, String code, Integer num) {
        // 1.??????killId????????????????????????????????????  Redis???
        BoundHashOperations<String, String, String> ops = redisTemplate.boundHashOps(SeckillConstant.SKU_CHACE_PREFIX);
        String json = ops.get(killId);
        if(StringUtils.isNotBlank(json)){
            SeckillSkuRedisDto dto = JSON.parseObject(json, SeckillSkuRedisDto.class);
            // ???????????????  1.???????????????
            Long startTime = dto.getStartTime();
            Long endTime = dto.getEndTime();
            long now = new Date().getTime();
            if(now > startTime && now < endTime){
                // ???????????????????????????????????????????????????
                // 2.?????? ??????????????? ????????????
                String randCode = dto.getRandCode();
                Long skuId = dto.getSkuId();
                String redisKillId = dto.getPromotionSessionId() + "_" + skuId;
                if(randCode.equals(code) && killId.equals(redisKillId)){
                    // ?????????????????????
                    // 3.????????????????????????????????????
                    if(num <= dto.getSeckillLimit().intValue()){
                        // ?????????????????????
                        // 4.?????????????????? ?????????
                        // ??????????????????????????????Redis??? ?????????????????? userId + sessionID + skuId
                        MemberVO memberVO = (MemberVO) AuthInterceptor.threadLocal.get();
                        Long id = memberVO.getId();
                        String redisKey = id + "_" + redisKillId;
                        Boolean aBoolean = redisTemplate.opsForValue()
                                .setIfAbsent(redisKey, num.toString(), (endTime - now), TimeUnit.MILLISECONDS);
                        if(aBoolean){
                            // ???????????????????????? ??????????????????
                            RSemaphore semaphore = redissonClient.getSemaphore(SeckillConstant.SKU_STOCK_SEMAPHORE+randCode);
                            try {
                                boolean b = semaphore.tryAcquire(num, 100, TimeUnit.MILLISECONDS);
                                if(b){
                                    // ??????????????????
                                    String orderSN = UUID.randomUUID().toString().replace("-", "");
                                    // ?????????????????????????????????  --> RocketMQ
                                    SeckillOrderDto orderDto = new SeckillOrderDto() ;
                                    orderDto.setOrderSN(orderSN);
                                    orderDto.setSkuId(skuId);
                                    orderDto.setSeckillPrice(dto.getSeckillPrice());
                                    orderDto.setMemberId(id);
                                    orderDto.setNum(num);
                                    orderDto.setPromotionSessionId(dto.getPromotionSessionId());
                                    // ??????RocketMQ ??????????????????
                                    rocketMQTemplate.sendOneWay(OrderConstant.ROCKETMQ_SECKILL_ORDER_TOPIC
                                            ,JSON.toJSONString(orderDto));
                                    return orderSN;
                                }
                            } catch (InterruptedException e) {
                                return null;
                            }
                        }
                    }

                }
            }
        }
        return null;
    }

    /**
     * ??????????????????????????????Redis???
     * @param seckillSessionEntities
     */
    private void saveSessionInfos(List<SeckillSessionEntity> seckillSessionEntities) {
        for (SeckillSessionEntity seckillSessionEntity : seckillSessionEntities) {
            // ???????????????????????????  key??? start_endTime
            long start = seckillSessionEntity.getStartTime().getTime();
            long end = seckillSessionEntity.getEndTime().getTime();
            // ??????Key
            String key = SeckillConstant.SESSION_CHACE_PREFIX+start+"_"+end;
            Boolean flag = redisTemplate.hasKey(key);
            if(!flag){// ???????????????????????????Redis????????????????????????????????????????????????????????????
                // ???????????????Redis????????????????????????????????????????????????????????????SKUID
                List<String> collect = seckillSessionEntity.getRelationEntities().stream().map(item -> {
                    // ????????????????????? VALUE??? sessionId_SkuId
                    return item.getPromotionSessionId()+"_"+item.getSkuId().toString();
                }).collect(Collectors.toList());
                redisTemplate.opsForList().leftPushAll(key,collect);
            }
        }
    }

    /**
     * ????????????????????? SKU??????
     * @param seckillSessionEntities
     */
    private void saveSessionSkuInfos(List<SeckillSessionEntity> seckillSessionEntities) {
        seckillSessionEntities.stream().forEach(session -> {
            // ??????????????????Session?????????????????????SkuID ?????????????????????
            BoundHashOperations<String, Object, Object> hashOps = redisTemplate.boundHashOps(SeckillConstant.SKU_CHACE_PREFIX);
            session.getRelationEntities().stream().forEach(item->{
                String skuKey = item.getPromotionSessionId()+"_"+item.getSkuId();
                Boolean flag = hashOps.hasKey(skuKey);
                if(!flag){
                    SeckillSkuRedisDto dto = new SeckillSkuRedisDto();
                    // 1.??????SKU???????????????
                    R info = productFeignService.info(item.getSkuId());
                    if(info.getCode() == 0){
                        // ??????????????????
                        String json = (String) info.get("skuInfoJSON");
                        dto.setSkuInfoVo(JSON.parseObject(json,SkuInfoVo.class));
                    }
                    // 2.??????SKU???????????????
                    /*dto.setSkuId(item.getSkuId());
                    dto.setSeckillPrice(item.getSeckillPrice());
                    dto.setSeckillCount(item.getSeckillCount());
                    dto.setSeckillLimit(item.getSeckillLimit());
                    dto.setSeckillSort(item.getSeckillSort());*/
                    BeanUtils.copyProperties(item,dto);
                    // 3.?????????????????????????????????
                    dto.setStartTime(session.getStartTime().getTime());
                    dto.setEndTime(session.getEndTime().getTime());

                    // 4. ?????????
                    String token = UUID.randomUUID().toString().replace("-","");
                    dto.setRandCode(token);
                    // ??????????????? ????????????
                    dto.setPromotionSessionId(item.getPromotionSessionId());
                    // ???????????????????????????  ???????????????
                    RSemaphore semaphore = redissonClient.getSemaphore(SeckillConstant.SKU_STOCK_SEMAPHORE + token);
                    // ??????????????????????????????????????????????????????????????????
                    semaphore.trySetPermits(item.getSeckillCount().intValue());
                    hashOps.put(skuKey,JSON.toJSONString(dto));
                }
            });
        });
    }


}
