package net.luffy.util;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;

/**
 * 音频格式转换器
 * 用于将不兼容的音频格式转换为QQ支持的AMR或SILK格式
 */
public class AudioFormatConverter {
    
    private static final String FFMPEG_PATH = "ffmpeg"; // 可配置为完整路径
    
    /**
     * 转换音频格式为AMR
     * @param inputStream 输入音频流
     * @param originalFormat 原始音频格式
     * @return 转换后的AMR格式音频流，如果转换失败返回原始流
     */
    public static InputStream convertToAMR(InputStream inputStream, String originalFormat) {
        if (inputStream == null) {
            return null;
        }
        
        // 如果已经是兼容格式，直接返回
        if (AudioFormatDetector.isQQCompatible(originalFormat)) {
            return inputStream;
        }
        
        try {
            // 创建临时文件
            Path tempInputFile = Files.createTempFile("audio_input_", getFileExtension(originalFormat));
            Path tempOutputFile = Files.createTempFile("audio_output_", ".amr");
            
            try {
                // 将输入流写入临时文件
                Files.copy(inputStream, tempInputFile, StandardCopyOption.REPLACE_EXISTING);
                
                // 使用FFmpeg进行转换
                boolean success = convertWithFFmpeg(tempInputFile.toString(), tempOutputFile.toString(), "amr");
                
                if (success && Files.exists(tempOutputFile) && Files.size(tempOutputFile) > 0) {
                    // 转换成功，返回转换后的文件流
                    // 已禁用控制台输出
                    // System.out.println("[音频转换] 成功将" + originalFormat + "格式转换为AMR格式");
                    return Files.newInputStream(tempOutputFile);
                } else {
                    // 已禁用控制台输出
                    // System.err.println("[音频转换] 转换失败，使用原始音频");
                    // 转换失败，返回原始流（重新读取临时输入文件）
                    return Files.newInputStream(tempInputFile);
                }
            } finally {
                // 清理临时文件（在JVM退出时删除）
                tempInputFile.toFile().deleteOnExit();
                tempOutputFile.toFile().deleteOnExit();
            }
        } catch (Exception e) {
            // 已禁用控制台输出
            // System.err.println("[音频转换] 转换过程中发生异常: " + e.getMessage());
            return inputStream; // 返回原始流
        }
    }
    
    /**
     * 使用FFmpeg进行音频格式转换
     * @param inputPath 输入文件路径
     * @param outputPath 输出文件路径
     * @param targetFormat 目标格式
     * @return 转换是否成功
     */
    private static boolean convertWithFFmpeg(String inputPath, String outputPath, String targetFormat) {
        try {
            ProcessBuilder processBuilder;
            
            if ("amr".equalsIgnoreCase(targetFormat)) {
                // 转换为AMR格式的命令
                processBuilder = new ProcessBuilder(
                    FFMPEG_PATH, 
                    "-i", inputPath,
                    "-ar", "8000",      // 采样率8kHz
                    "-ac", "1",         // 单声道
                    "-ab", "12.2k",     // 比特率12.2kbps
                    "-f", "amr",        // 输出格式AMR
                    "-y",               // 覆盖输出文件
                    outputPath
                );
            } else {
                // 其他格式的通用转换命令
                processBuilder = new ProcessBuilder(
                    FFMPEG_PATH,
                    "-i", inputPath,
                    "-y",
                    outputPath
                );
            }
            
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();
            
            // 等待转换完成，最多等待30秒
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            
            if (!finished) {
                process.destroyForcibly();
                // 已禁用控制台输出
                // System.err.println("[音频转换] FFmpeg转换超时");
                return false;
            }
            
            int exitCode = process.exitValue();
            if (exitCode == 0) {
                // 已禁用控制台输出
                // System.out.println("[音频转换] FFmpeg转换成功");
                return true;
            } else {
                // 已禁用控制台输出
                // System.err.println("[音频转换] FFmpeg转换失败，退出码: " + exitCode);
                // 读取错误输出
                // try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                //     String line;
                //     while ((line = reader.readLine()) != null) {
                //         System.err.println("[FFmpeg] " + line);
                //     }
                // }
                return false;
            }
        } catch (Exception e) {
            // 已禁用控制台输出
            // System.err.println("[音频转换] FFmpeg执行异常: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 根据音频格式获取文件扩展名
     * @param format 音频格式
     * @return 文件扩展名
     */
    private static String getFileExtension(String format) {
        if (format == null) {
            return ".tmp";
        }
        
        switch (format.toUpperCase()) {
            case "MP3":
                return ".mp3";
            case "WAV":
                return ".wav";
            case "AAC":
                return ".aac";
            case "M4A":
                return ".m4a";
            case "OGG":
                return ".ogg";
            case "AMR":
                return ".amr";
            case "SILK":
                return ".silk";
            default:
                return ".tmp";
        }
    }
    
    /**
     * 检查FFmpeg是否可用
     * @return true表示FFmpeg可用，false表示不可用
     */
    public static boolean isFFmpegAvailable() {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(FFMPEG_PATH, "-version");
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();
            
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            
            return process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * 获取转换器状态信息
     * @return 状态信息字符串
     */
    public static String getConverterStatus() {
        boolean ffmpegAvailable = isFFmpegAvailable();
        return String.format("[音频转换器状态] FFmpeg可用: %s", ffmpegAvailable ? "是" : "否");
    }
}