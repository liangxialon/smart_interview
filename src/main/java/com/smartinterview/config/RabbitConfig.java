package com.smartinterview.config;

import com.rabbitmq.client.ConnectionFactory;
import com.smartinterview.common.constants.RabbitConstants;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;



@Configuration
public class RabbitConfig {

    //配置消息转换器
    @Bean
    public  MessageConverter messageRabbitConverter(){
        return new Jackson2JsonMessageConverter();
    }
   //开启队列持久化
   @Bean
    public Queue resumeParseQueue(){
        return new Queue(RabbitConstants.RESUME_PARSE_QUEUE,true);
    }
    //交换机持久化，关闭自动删除
    @Bean
    public DirectExchange directParseExchange(){
       return new DirectExchange(RabbitConstants.RESUME_PARSE_EXCHANGE,true,false);
    }
    //队列绑定到交换机
    @Bean
    public Binding bindingParse(Queue resumeParseQueue ,DirectExchange directParseExchange){
       return BindingBuilder.bind(resumeParseQueue).to(directParseExchange).with(RabbitConstants.RESUME_PARSE_ROUTING_KEY);
    }
    @Bean
    public Queue interviewScoreQueue(){return new Queue(RabbitConstants.INTERVIEW_SCORE_QUEUE,true);}
    @Bean
    public DirectExchange interviewScoreExchange(){return new DirectExchange(RabbitConstants.INTERVIEW_SCORE_EXCHANGE,true,false);}
    @Bean
    public Binding bingInterviewScoreExchange(Queue interviewScoreQueue,DirectExchange interviewScoreExchange){
        return BindingBuilder.bind(interviewScoreQueue).to(interviewScoreExchange).with(RabbitConstants.INTERVIEW_SCORE_ROUTING_KEY);
    }
}
