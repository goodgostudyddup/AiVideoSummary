package com.example.aispringvideo;
import com.baomidou.mybatisplus.generator.AutoGenerator;
import com.baomidou.mybatisplus.generator.FastAutoGenerator;
import com.baomidou.mybatisplus.generator.config.*;
import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.generator.config.rules.DbColumnType;
import com.baomidou.mybatisplus.generator.config.rules.NamingStrategy;
import com.baomidou.mybatisplus.generator.engine.FreemarkerTemplateEngine;

import java.sql.Types;
import java.util.Collections;


/**
 * MyBatis-Plus 代码生成器
 * 运行 main 方法即可根据数据库表自动生成 Entity、Mapper、Service、Controller
 */
public class CodeGenerator {
    public static void main(String[] args) {
        FastAutoGenerator.create("jdbc:mysql://localhost:3306/video", "root", "root")
                .globalConfig(builder -> {
                    builder.author("yxc") // 设置作者
//                            .enableSwagger() // 开启 swagger 模式
                            .outputDir("C:\\Users\\yxc\\Desktop\\新建文件夹"); // 指定输出目录
                })
                .dataSourceConfig(builder ->
                        builder.typeConvertHandler((globalConfig, typeRegistry, metaInfo) -> {
                            int typeCode = metaInfo.getJdbcType().TYPE_CODE;
                            if (typeCode == Types.SMALLINT) {
                                // 自定义类型转换
                                return DbColumnType.INTEGER;
                            }
                            return typeRegistry.getColumnType(metaInfo);
                        })
                )
                .packageConfig(builder ->
                        builder.parent("com.example.aispringvideo") // 设置父包名
                                .moduleName("") // 设置父包模块名
                                .pathInfo(Collections.singletonMap(OutputFile.xml, "C:\\Users\\yxc\\Desktop\\新建文件夹")) // 设置mapperXml生成路径
                )
                .strategyConfig(builder ->
                        builder.addInclude("video_task") // 设置需要生成的表名
                                .addTablePrefix("t_", "c_") // 设置过滤表前缀
                                .entityBuilder()
                                .enableLombok() // 开启 lombok 模型
                                .controllerBuilder()
                                // 开启生成 @RestController 控制器
                                .enableRestStyle()
                                // 开启驼峰转连字符
                                .enableHyphenStyle()
                                // 先开启 Entity 配置
                                .entityBuilder()
                                // 开启主键自增
                                .idType(IdType.AUTO)
                                // 数据库表映射到实体的命名策略，驼峰命名
                                .naming(NamingStrategy.underline_to_camel)
                                // 数据库表字段映射到实体的命名策略，驼峰命名
                                .columnNaming(NamingStrategy.underline_to_camel)
                                // 开启生成实体时生成字段注解
                                .enableTableFieldAnnotation())

                .templateEngine(new FreemarkerTemplateEngine()) // 使用Freemarker引擎模板，默认的是Velocity引擎模板
                .execute();
    }

}