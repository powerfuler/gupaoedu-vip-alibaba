package com.gupaoedu.gpmall.marking.markingserviceprovider.service;

import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gupaoedu.gpmall.exception.BizException;
import com.gupaoedu.gpmall.marking.ILotterService;
import com.gupaoedu.gpmall.marking.dto.DrawRequest;
import com.gupaoedu.gpmall.marking.dto.DrawResponse;
import com.gupaoedu.gpmall.marking.dto.UserDrawChanceRequest;
import com.gupaoedu.gpmall.marking.dto.UserDrawChanceResponse;
import com.gupaoedu.gpmall.marking.enums.MmsResCodeEnum;
import com.gupaoedu.gpmall.marking.markingserviceprovider.biz.AbstractRewardProcessor;
import com.gupaoedu.gpmall.marking.markingserviceprovider.biz.RewardContext;
import com.gupaoedu.gpmall.marking.markingserviceprovider.dal.mapper.*;
import com.gupaoedu.gpmall.marking.markingserviceprovider.dal.model.MmsLottery;
import com.gupaoedu.gpmall.marking.markingserviceprovider.dal.model.MmsLotteryChance;
import com.gupaoedu.gpmall.marking.markingserviceprovider.dal.model.MmsLotteryItem;
import com.gupaoedu.gpmall.marking.markingserviceprovider.event.InitPrizeToRedisEvent;
import com.gupaoedu.gpmall.marking.markingserviceprovider.utils.constants.LotteryConstants;
import com.gupaoedu.gpmall.marking.markingserviceprovider.utils.constants.RedisKeyManager;
import com.gupaoedu.gpmall.marking.markingserviceprovider.utils.exception.MmsExceptionWrapper;
import com.gupaoedu.gpmall.marking.markingserviceprovider.utils.exception.RewardException;
import com.gupaoedu.gpmall.marking.markingserviceprovider.utils.exception.UnRewardException;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * ???????????????ToBeBetterMan
 * Mic????????????: mic4096
 * ?????????????????? ??????Mic?????????
 * https://ke.gupaoedu.cn
 **/
@Slf4j
@DubboService
public class LotterService implements ILotterService {

    @Autowired
    MmsLotteryMapper lotteryMapper;
    @Autowired
    MmsLotteryPrizeMapper lotteryPrizeMapper;
    @Autowired
    MmsLotteryRecordMapper mmsLotteryRecordMapper;
    @Autowired
    MmsLotteryItemMapper lotteryItemMapper;
    @Autowired
    MmsLotteryChanceMapper lotteryChanceMapper;
    @Autowired
    ApplicationContext applicationContext;
    @Autowired
    RedisTemplate redisTemplate;

    private static final int mulriple = 10000;

    @Override
    public DrawResponse doDraw(DrawRequest request) {
        log.info("[LotterService.doDraw, request : {}",request);
        DrawResponse response=new DrawResponse();
        RewardContext context = new RewardContext();
        MmsLotteryItem lotteryItem = null;
        try {
            checkDrawParams(request);
            CountDownLatch countDownLatch = new CountDownLatch(1);
            //?????????????????????
            MmsLottery lottery = checkLottery(request);
            //??????????????????????????????????????????????????????
            applicationContext.publishEvent(new InitPrizeToRedisEvent(this,lottery.getId(), countDownLatch));
            //????????????
            lotteryItem = doPlay(lottery);
            //???????????????????????????
            countDownLatch.await(); //???????????????????????????
            String key = RedisKeyManager.getLotteryPrizeRedisKey(lottery.getId(), lotteryItem.getPrizeId());
            int prizeType = Integer.parseInt(redisTemplate.opsForHash().get(key, "prizeType").toString());
            context.setLottery(lottery);
            context.setLotteryItem(lotteryItem);
            context.setAccountIp(request.getAccount());
            context.setKey(key);
            //?????????????????????????????????
            AbstractRewardProcessor.rewardProcessorMap.get(prizeType).doReward(context);
            //??????????????????
            response.setLevel(lotteryItem.getLevel());
            response.setPrizeName(context.getPrizeName());
            response.setPrizeId(context.getPrizeId());
            response.setCode(MmsResCodeEnum.SYS_SUCCESS.getCode());
            response.setMsg(MmsResCodeEnum.SYS_SUCCESS.getMsg());
        } catch (UnRewardException u) { //????????????????????????????????????????????????????????????
            context.setKey(RedisKeyManager.getDefaultLotteryPrizeRedisKey(lotteryItem.getLotteryId()));
            lotteryItem=(MmsLotteryItem) redisTemplate.opsForValue().get(RedisKeyManager.getDefaultLotteryItemRedisKey(lotteryItem.getLotteryId()));
            context.setLotteryItem(lotteryItem);
            AbstractRewardProcessor.rewardProcessorMap.get(LotteryConstants.PrizeTypeEnum.THANK.getValue()).doReward(context);
        }catch (Exception e){
            log.error("[LotterService.doDraw], occur Exception",e);
            MmsExceptionWrapper.handlerException4biz(response,e);
        }finally {
            //??????????????????
            redisTemplate.delete(RedisKeyManager.getDrawingRedisKey(request.getAccount()));
        }
        return response;
    }


    private void checkDrawParams(DrawRequest request){
        if(null==request.getLotteryId()|| StringUtils.isEmpty(request.getAccount())){
            throw new RewardException(MmsResCodeEnum.SYS_PARAM_NOT_NULL.getCode(),MmsResCodeEnum.SYS_PARAM_NOT_NULL.getMsg());
        }
        //??????setNx??????????????????????????????????????????????????????
        Boolean result=redisTemplate.opsForValue().setIfAbsent(RedisKeyManager.getDrawingRedisKey(request.getAccount()),"1", 60, TimeUnit.SECONDS);
        //?????????false????????????????????????????????????
        if(!result){
            throw new RewardException(MmsResCodeEnum.LOTTER_DRAWING.getCode(),MmsResCodeEnum.LOTTER_DRAWING.getMsg());
        }
    }

    /**
     * ?????????????????????????????????
     *
     * @param request
     * @return
     */
    private MmsLottery checkLottery(DrawRequest request) {
        MmsLottery lottery;
        Object lotteryJsonStr = redisTemplate.opsForValue().get(RedisKeyManager.getLotteryRedisKey(request.getLotteryId()));
        if (null != lotteryJsonStr) {
            lottery = JSON.parseObject(lotteryJsonStr.toString(), MmsLottery.class);
        } else {
            lottery = lotteryMapper.selectById(request.getLotteryId());
        }
        if (lottery == null) {
            throw new BizException(MmsResCodeEnum.LOTTER_NOT_EXIST.getCode(), MmsResCodeEnum.LOTTER_NOT_EXIST.getMsg());
        }
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(lottery.getStartTime()) || now.isAfter(lottery.getEndTime())) {
            throw new BizException(MmsResCodeEnum.LOTTER_FINISH.getCode(), MmsResCodeEnum.LOTTER_FINISH.getMsg());
        }
        //???????????????????????????
        int rows=lotteryChanceMapper.updateLotteryChance(Integer.parseInt(request.getAccount()));
        if(rows<=0){ //????????????
            throw new BizException(MmsResCodeEnum.NOT_ENOUGH_LOTTER_CHANCE.getCode(), MmsResCodeEnum.NOT_ENOUGH_LOTTER_CHANCE.getMsg());
        }
        return lottery;
    }

    //????????????
    private MmsLotteryItem doPlay(MmsLottery lottery) {
        MmsLotteryItem lotteryItem = null;
        QueryWrapper<MmsLotteryItem> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("lottery_id", lottery.getId());
        Object lotteryItemsObj=redisTemplate.opsForValue().get(RedisKeyManager.getLotteryItemRedisKey(lottery.getId()));
        List<MmsLotteryItem> lotteryItems;
        //???????????????????????????????????????????????????????????????????????????????????????
        if(lotteryItemsObj==null){
            lotteryItems = lotteryItemMapper.selectList(queryWrapper);
        }else{
            lotteryItems=(List<MmsLotteryItem>)lotteryItemsObj;
        }
        //?????????????????????
        if (lotteryItems.isEmpty()) {
            throw new BizException(MmsResCodeEnum.LOTTER_ITEM_NOT_INITIAL.getCode(), MmsResCodeEnum.LOTTER_ITEM_NOT_INITIAL.getMsg());
        }
        int lastScope = 0;
        Collections.shuffle(lotteryItems);
        Map<Integer, int[]> awardItemScope = new HashMap<>();
        //item.getPercent=0.05 = 5%
        for (MmsLotteryItem item : lotteryItems) {
            int currentScope = lastScope + new BigDecimal(item.getPercent().floatValue()).multiply(new BigDecimal(mulriple)).intValue();
            awardItemScope.put(item.getId(), new int[]{lastScope + 1, currentScope});
            lastScope = currentScope;
        }
        int luckyNumber = new Random().nextInt(mulriple);
        int luckyPrizeId = 0;
        if (!awardItemScope.isEmpty()) {
            Set<Map.Entry<Integer, int[]>> set = awardItemScope.entrySet();
            for (Map.Entry<Integer, int[]> entry : set) {
                if (luckyNumber >= entry.getValue()[0] && luckyNumber <= entry.getValue()[1]) {
                    luckyPrizeId = entry.getKey();
                    break;
                }
            }
        }
        for (MmsLotteryItem item : lotteryItems) {
            if (item.getId().intValue() == luckyPrizeId) {
                lotteryItem = item;
                break;
            }
        }
        return lotteryItem;
    }
}
