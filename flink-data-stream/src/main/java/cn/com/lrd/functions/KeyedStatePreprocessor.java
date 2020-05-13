package cn.com.lrd.functions;

import com.commerce.commons.enumeration.EStep;
import com.commerce.commons.model.EsDosage;
import com.commerce.commons.model.EsDosagePhase;
import com.commerce.commons.model.InputDataSingle;
import com.commerce.commons.utils.DateUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.flink.api.common.state.*;
import org.apache.flink.api.common.time.Time;
import org.apache.flink.api.common.typeinfo.TypeHint;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.tuple.Tuple;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.tuple.Tuple6;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;
import org.apache.flink.util.OutputTag;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Date;

/**
 * @description: 用量统计, 原始数据 10分钟一条, 统计出, 半小时,一小时,一天,一月的用量.  输出到侧输出流, Sink到不同的数据库
 * @author: zhangdongsheng
 * @date: 2020/5/12 16:37
 */
@Slf4j
public class KeyedStatePreprocessor extends KeyedProcessFunction<Tuple, Tuple6<String, Tuple2<LocalDateTime, LocalDateTime>, Tuple2<LocalDateTime, LocalDateTime>, Tuple2<LocalDateTime, LocalDateTime>, Tuple2<LocalDateTime, LocalDateTime>, InputDataSingle>, EsDosagePhase> {
    public static final OutputTag<EsDosage> halfTimeOutputTag = new OutputTag<EsDosage>("halfTime") {
    };
    public static final OutputTag<EsDosage> hourTimeOutputTag = new OutputTag<EsDosage>("hourTime") {
    };
    public static final OutputTag<EsDosage> dayTimeOutputTag = new OutputTag<EsDosage>("dayTime") {
    };
    public static final OutputTag<EsDosage> monthTimeOutputTag = new OutputTag<EsDosage>("monthTime") {
    };


    private ValueState<Double> lastState;
    private ValueState<Long> lastTime;
    private MapState<String, Double> timeState;
    private MapState<String, Double> monthTimeState;


    @Override
    public void open(Configuration parameters) throws Exception {
        MapStateDescriptor<String, Double> timeStateDescriptor = new MapStateDescriptor<>("timeState",
                TypeInformation.of(new TypeHint<String>() {
                }),
                TypeInformation.of(new TypeHint<Double>() {
                }));
        // 状态 TTL 相关配置，过期时间设定为 36 小时
        StateTtlConfig ttlConfig = StateTtlConfig
                .newBuilder(Time.hours(36))
                .setUpdateType(StateTtlConfig.UpdateType.OnCreateAndWrite)
                .setStateVisibility(StateTtlConfig.StateVisibility.NeverReturnExpired)
                .cleanupIncrementally(10, false)
                .build();
        // 开启 TTL
        timeStateDescriptor.enableTimeToLive(ttlConfig);
        // 从状态中恢复 timeState
        this.timeState = getRuntimeContext().getMapState(timeStateDescriptor);

        MapStateDescriptor<String, Double> monthTimeStateDescriptor = new MapStateDescriptor<>("monthTimeState",
                TypeInformation.of(new TypeHint<String>() {
                }),
                TypeInformation.of(new TypeHint<Double>() {
                }));
        // 状态 TTL 相关配置，过期时间设定为 66天
        StateTtlConfig ttlMonthConfig = StateTtlConfig
                .newBuilder(Time.days(66))
                .setUpdateType(StateTtlConfig.UpdateType.OnCreateAndWrite)
                .setStateVisibility(StateTtlConfig.StateVisibility.NeverReturnExpired)
                .cleanupIncrementally(10, false)
                .build();
        // 开启 TTL
        monthTimeStateDescriptor.enableTimeToLive(ttlMonthConfig);
        // 从状态中恢复 timeState
        this.monthTimeState = getRuntimeContext().getMapState(monthTimeStateDescriptor);

        ValueStateDescriptor<Double> lastState = new ValueStateDescriptor<>("lastState", TypeInformation.of(new TypeHint<Double>() {
        }));
        lastState.enableTimeToLive(ttlConfig);
        this.lastState = getRuntimeContext().getState(lastState);
        ValueStateDescriptor<Long> lastTime = new ValueStateDescriptor<>("lastState", TypeInformation.of(new TypeHint<Long>() {
        }));
        lastTime.enableTimeToLive(ttlConfig);
        this.lastTime = getRuntimeContext().getState(lastTime);
    }

    @Override
    public void processElement(Tuple6<String, Tuple2<LocalDateTime, LocalDateTime>, Tuple2<LocalDateTime, LocalDateTime>, Tuple2<LocalDateTime, LocalDateTime>, Tuple2<LocalDateTime, LocalDateTime>, InputDataSingle> value, Context ctx, Collector<EsDosagePhase> out) throws Exception {
        InputDataSingle inputDataSingle = value.f5;
        long longTime = DateUtil.parseStrDateTime(inputDataSingle.getTime());
        //这里过滤一下乱序数据, 一个feedId 10分钟的基数出现乱序数据就不处理
        if (lastTime.value() != null && longTime < lastTime.value()) {
            log.info("乱序数据不处理>>>{}", inputDataSingle);
            return;
        }
        lastTime.update(longTime);

        EsDosagePhase esDosagePhase = new EsDosagePhase(inputDataSingle.getFeedId() + "_" + inputDataSingle.getTime(), inputDataSingle.getFeedId(), inputDataSingle.getCode(), inputDataSingle.getValue(), inputDataSingle.getTime(), new Date(), new Date());
        out.collect(esDosagePhase);

        //当前Feed 上一笔数据
        BigDecimal lastFeedValue = null;
        if (lastState.value() != null)
            lastFeedValue = BigDecimal.valueOf(lastState.value());
        //当前Feed 现在的数据
        BigDecimal currentFeedValue = BigDecimal.valueOf(inputDataSingle.getValue());
        lastState.update(currentFeedValue.doubleValue());
        //现在的数据与上一笔数据的差值
        Double subtract = 0.0;
        if (lastFeedValue != null)
            subtract = currentFeedValue.subtract(lastFeedValue).doubleValue();

        Tuple2<LocalDateTime, LocalDateTime> halfTime = value.f1;
        outPutData(timeState, halfTime, ctx, esDosagePhase, subtract, EStep.THIRTY_MINUTE.getName(), halfTimeOutputTag);

        Tuple2<LocalDateTime, LocalDateTime> hourTime = value.f3;
        outPutData(timeState, hourTime, ctx, esDosagePhase, subtract, EStep.ONE_HOUR.getName(), hourTimeOutputTag);

        Tuple2<LocalDateTime, LocalDateTime> dayTime = value.f3;
        outPutData(timeState, dayTime, ctx, esDosagePhase, subtract, EStep.ONE_DAY.getName(), dayTimeOutputTag);

        Tuple2<LocalDateTime, LocalDateTime> monthTime = value.f4;
        outPutData(monthTimeState, monthTime, ctx, esDosagePhase, subtract, EStep.ONE_MONTH.getName(), monthTimeOutputTag);
    }

    private void outPutData(MapState<String, Double> curState, Tuple2<LocalDateTime, LocalDateTime> tupleTime, Context ctx, EsDosagePhase esDosagePhase, Double subtract, String step, OutputTag<EsDosage> outputTag) throws Exception {
        String key = DateUtil.formatTime(tupleTime.f0) + "_" + DateUtil.formatTime(tupleTime.f1);
        if (curState.get(key) == null) {
            curState.put(key, 0.0);
        } else {
            Double aDouble = curState.get(key);
            curState.put(key, aDouble + subtract);
        }
        EsDosage esDosage = new EsDosage(esDosagePhase.getId(), esDosagePhase.getFeed_id(), curState.get(key),
                DateUtil.toEpochSecond(tupleTime.f0), DateUtil.toEpochSecond(tupleTime.f1), DateUtil.formatLocalDateTime(tupleTime.f1)
                , step, new Date(), new Date());

        ctx.output(outputTag, esDosage);
    }

}