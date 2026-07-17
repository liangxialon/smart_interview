package com.smartinterview;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONUtil;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.smartinterview.common.exception.BaseException;
import com.smartinterview.entity.InterviewSession;
import com.smartinterview.entity.QuestionVector;
import com.smartinterview.service.InterviewSessionService;
import com.smartinterview.service.impl.InterviewSessionServiceImpl;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

@SpringBootTest
@Slf4j
class SmartInterviewApplicationTests {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Data
    class Student{
        private String name;
        private int age;

        public Student(String name, int age) {
            this.name = name;
            this.age = age;
        }

        public Student() {
        }
        public Student(String s){
            this.name=s.split(",")[0];
            this.age=Integer.parseInt(s.split(",")[1]);
        }
    }

    @Test
    void contextLoads() {
    }
//    @Test
//    void testException(){
//        throw new BaseException("测试异常");
//    }
    @Test
    public void testExtractTextFromPdf() {
        // 1. 找一个你电脑本地的 PDF 简历路径进行测试
        String pdfFilePath = "C:\\Users\\32341\\Desktop\\360.pdf";
        File file = new File(pdfFilePath);

        PDDocument document = null;
        try {
            // 2. 加载 PDF 文档
            document = PDDocument.load(file);

            // 3. 实例化文本提取器
            PDFTextStripper stripper = new PDFTextStripper();

            // 按阅读顺序排序（对于简历这种多栏排版的文档非常重要）
            stripper.setSortByPosition(true);

            // 4. 执行提取
            String text = stripper.getText(document);

            System.out.println("====== 简历解析结果开始 ======");
            System.out.println(text);
            System.out.println("====== 简历解析结果结束 ======");

        } catch (IOException e) {
            System.err.println("PDF 解析失败: " + e.getMessage());
        } finally {
            // 5. 必须关闭文档释放内存
            if (document != null) {
                try {
                    document.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    @Autowired
    private InterviewSessionServiceImpl interviewSessionService;
    @Test
    public void testDTO(){
        InterviewSession interviewSession=InterviewSession.builder().
                userId(2L).
                difficulty("无").
                createTime(LocalDateTime.now()).build();
        interviewSessionService.save(interviewSession);
    }
    @Test
    public void testRedis(){
        Message userMsg=Message.builder()
                .role(Role.USER.getValue())
                .content("你好这是我的简历")
                .build();
          //对象与字符串之间的转变：hutool工具包/ FastJSON（阿里开源 JSON 库）
//        // 1. 对象转 JSON 字符串
//        String jsonStr = JSONUtil.toJsonStr(message);
//
//        // 2. JSON 字符串转对象
//        Message msg = JSONUtil.toBean(jsonStr, Message.class);
        String jsonStr=JSONUtil.toJsonStr(userMsg);

        stringRedisTemplate.opsForList().rightPush("userMsg",jsonStr);
        String userMsg1 = stringRedisTemplate.opsForList().index("userMsg", 0);
        Message msg=JSONUtil.toBean(userMsg1,Message.class,false);
        System.out.println("userMsg1:"+msg.getContent());
    }
    @Test
    public void test3(){
        List<String> list=new ArrayList();
        Collections.addAll(list,"大明,10","韩梅梅,18","光龙,20");
        //这三种方式都是为了实现函数式接口
      //匿名内部类
        list.stream().map(new Function<String,Student>(){
            @Override
            public Student apply(String o) {
                String[] r = o.split(",");
                String name=r[0];
                int age=Integer.parseInt(r[1]);
                return new Student(name,age);
            }
        }).forEach(s->System.out.println(s));
        //lambda表达式  参数跟返回值也要跟抽象方法的形参跟返回值保持一至
        list.stream().map(s-> new Student(
                s.split(",")[0], Integer.parseInt(s.split(",")[1])
                ))
                .forEach(s->System.out.println(s));

    //方法引用  被引用的方法形参跟返回值需要跟抽象方法的形参返回值一样
    //引用类名：：方法名（静态）    对象名：：方法名 （别的类）   this::方法名(引用自己类的方法)    类名：：new（引用构造方法）
    list.stream().map(Student::new).forEach(s->System.out.println(s));
}

}
