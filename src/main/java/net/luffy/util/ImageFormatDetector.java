package net.luffy.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.util.Iterator;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * 图片格式检测器
 * 用于检测图片文件格式并判断兼容性
 * 增强版：支持更多格式检测，改进错误处理，添加详细日志记录
 */
public class ImageFormatDetector {
    
    private static final Logger logger = Logger.getLogger(ImageFormatDetector.class.getName());
    
    static {
        // 配置日志级别，不在控制台显示
        logger.setLevel(Level.INFO);
        logger.setUseParentHandlers(false);
    }
    
    /**
     * 检测图片格式
     * @param file 图片文件
     * @return 图片格式字符串（如 "JPEG", "PNG", "GIF" 等），如果无法识别则返回 "UNKNOWN"
     */
    public static String detectFormat(File file) {
        if (file == null || !file.exists() || !file.isFile()) {
            logger.warning("文件不存在或不是有效文件: " + (file != null ? file.getAbsolutePath() : "null"));
            return "UNKNOWN";
        }
        
        logger.info("开始检测图片格式: " + file.getAbsolutePath() + ", 文件大小: " + file.length() + " bytes");
        
        try (FileInputStream fis = new FileInputStream(file);
             BufferedInputStream bis = new BufferedInputStream(fis, 8192)) {
            String format = detectFormat(bis);
            logger.info("图片格式检测结果: " + format + ", 文件: " + file.getAbsolutePath());
            return format;
        } catch (IOException e) {
            logger.severe("读取文件失败: " + e.getMessage() + ", 文件: " + file.getAbsolutePath());
            return "UNKNOWN";
        }
    }
    
    /**
     * 检测图片格式（增强版）
     * @param inputStream 图片输入流
     * @return 图片格式字符串（如 "JPEG", "PNG", "GIF" 等），如果无法识别则返回 "UNKNOWN"
     */
    public static String detectFormat(InputStream inputStream) {
        if (inputStream == null) {
            logger.warning("输入流为null");
            return "UNKNOWN";
        }
        
        // 确保输入流支持mark/reset
        BufferedInputStream bis = inputStream instanceof BufferedInputStream ? 
                (BufferedInputStream) inputStream : 
                new BufferedInputStream(inputStream, 8192);
        
        try {
            // 标记当前位置，以便后续重置
            bis.mark(64); // 增加标记缓冲区大小
            
            // 读取更多文件头字节进行格式检测
            byte[] header = new byte[32]; // 增加读取字节数
            int bytesRead = bis.read(header, 0, header.length);
            bis.reset(); // 重置流位置
            
            logger.info("读取文件头: " + bytesRead + " bytes, 头部数据: " + bytesToHex(header, Math.min(16, bytesRead)));
            
            if (bytesRead > 0) {
                // 通过文件头魔数检测格式
                String format = detectFormatByHeader(header, bytesRead);
                if (!"UNKNOWN".equals(format)) {
                    logger.info("通过文件头检测到格式: " + format);
                    return format;
                }
            }
            
            // 如果通过文件头无法识别，尝试使用ImageIO
            bis.mark(2 * 1024 * 1024); // 增加标记缓冲区大小到2MB
            try {
                ImageInputStream iis = ImageIO.createImageInputStream(bis);
                if (iis != null) {
                    Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
                    if (readers.hasNext()) {
                        ImageReader reader = readers.next();
                        String formatName = reader.getFormatName().toUpperCase();
                        logger.info("通过ImageIO检测到格式: " + formatName);
                        reader.dispose();
                        iis.close();
                        return formatName;
                    }
                    iis.close();
                } else {
                    logger.warning("无法创建ImageInputStream");
                }
            } catch (Exception e) {
                logger.warning("ImageIO检测失败: " + e.getMessage());
            }
            
            bis.reset(); // 重置流位置
            
        } catch (IOException e) {
            logger.severe("读取流失败: " + e.getMessage());
        }
        
        logger.warning("无法识别图片格式");
        return "UNKNOWN";
    }
    
    /**
     * 通过文件头魔数检测图片格式（增强版）
     * @param header 文件头字节数组
     * @param bytesRead 实际读取的字节数
     * @return 图片格式字符串
     */
    private static String detectFormatByHeader(byte[] header, int bytesRead) {
        if (header == null || bytesRead < 2) {
            logger.warning("文件头数据不足，无法检测格式");
            return "UNKNOWN";
        }
        
        // JPEG/JPG格式 (FF D8 FF) - 更严格的检测
        if (bytesRead >= 3 && header[0] == (byte) 0xFF && header[1] == (byte) 0xD8 && header[2] == (byte) 0xFF) {
            logger.info("检测到JPEG格式标识");
            return "JPEG";
        }
        
        // JPEG的另一种变体 (FF D8 FF E0 或 FF D8 FF E1)
        if (bytesRead >= 4 && header[0] == (byte) 0xFF && header[1] == (byte) 0xD8 && header[2] == (byte) 0xFF && 
            (header[3] == (byte) 0xE0 || header[3] == (byte) 0xE1)) {
            logger.info("检测到JPEG格式标识（变体）");
            return "JPEG";
        }
        
        // PNG格式 (89 50 4E 47 0D 0A 1A 0A) - 完整的PNG签名
        if (bytesRead >= 8 && header[0] == (byte) 0x89 && header[1] == 'P' && header[2] == 'N' && header[3] == 'G' &&
            header[4] == 0x0D && header[5] == 0x0A && header[6] == 0x1A && header[7] == 0x0A) {
            logger.info("检测到PNG格式标识");
            return "PNG";
        }
        
        // GIF格式 (47 49 46 38 37 61 或 47 49 46 38 39 61)
        if (bytesRead >= 6 && header[0] == 'G' && header[1] == 'I' && header[2] == 'F' && header[3] == '8' &&
            (header[4] == '7' || header[4] == '9') && header[5] == 'a') {
            logger.info("检测到GIF格式标识");
            return "GIF";
        }
        
        // BMP格式 (42 4D)
        if (bytesRead >= 2 && header[0] == 'B' && header[1] == 'M') {
            logger.info("检测到BMP格式标识");
            return "BMP";
        }
        
        // WEBP格式 (52 49 46 46 ... 57 45 42 50)
        if (bytesRead >= 12 && header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F' 
                && header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P') {
            logger.info("检测到WEBP格式标识");
            return "WEBP";
        }
        
        // TIFF格式 (49 49 2A 00 或 4D 4D 00 2A)
        if (bytesRead >= 4 && ((header[0] == 'I' && header[1] == 'I' && header[2] == '*' && header[3] == 0) ||
            (header[0] == 'M' && header[1] == 'M' && header[2] == 0 && header[3] == '*'))) {
            logger.info("检测到TIFF格式标识");
            return "TIFF";
        }
        
        // ICO格式 (00 00 01 00)
        if (bytesRead >= 4 && header[0] == 0 && header[1] == 0 && header[2] == 1 && header[3] == 0) {
            logger.info("检测到ICO格式标识");
            return "ICO";
        }
        
        // SVG格式检测（XML开头）
        if (bytesRead >= 5) {
            String headerStr = new String(header, 0, Math.min(bytesRead, 20)).toLowerCase();
            if (headerStr.startsWith("<?xml") || headerStr.startsWith("<svg")) {
                logger.info("检测到SVG格式标识");
                return "SVG";
            }
        }
        
        logger.info("未能通过文件头识别格式，头部数据: " + bytesToHex(header, Math.min(16, bytesRead)));
        return "UNKNOWN";
    }
    
    /**
     * 判断图片格式是否为QQ支持的格式（增强版）
     * @param format 图片格式
     * @return true表示支持，false表示不支持
     */
    public static boolean isQQCompatible(String format) {
        if (format == null) {
            logger.warning("格式参数为null");
            return false;
        }
        
        boolean compatible;
        switch (format.toUpperCase()) {
            case "JPEG":
            case "JPG":
            case "PNG":
            case "GIF":
            case "BMP":
                compatible = true;
                break;
            case "WEBP":
                // WEBP在某些QQ版本中支持，但为了兼容性，建议转换
                compatible = false;
                break;
            default:
                compatible = false;
                break;
        }
        
        logger.info("格式兼容性检查: " + format + " -> " + (compatible ? "兼容" : "不兼容"));
        return compatible;
    }
    
    /**
     * 验证图片文件的完整性
     * @param file 图片文件
     * @return true表示文件完整，false表示文件可能损坏
     */
    public static boolean validateImageIntegrity(File file) {
        if (file == null || !file.exists() || file.length() == 0) {
            logger.warning("文件不存在或为空: " + (file != null ? file.getAbsolutePath() : "null"));
            return false;
        }
        
        try {
            // 尝试读取图片以验证完整性
            java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(file);
            if (image != null) {
                logger.info("图片完整性验证通过: " + file.getAbsolutePath() + ", 尺寸: " + image.getWidth() + "x" + image.getHeight());
                return true;
            } else {
                logger.warning("图片完整性验证失败，无法读取: " + file.getAbsolutePath());
                return false;
            }
        } catch (Exception e) {
            logger.warning("图片完整性验证异常: " + e.getMessage() + ", 文件: " + file.getAbsolutePath());
            return false;
        }
    }
    
    /**
     * 将字节数组转换为十六进制字符串（用于调试）
     * @param bytes 字节数组
     * @param length 要转换的长度
     * @return 十六进制字符串
     */
    public static String bytesToHex(byte[] bytes, int length) {
        if (bytes == null) {
            return "null";
        }
        
        StringBuilder sb = new StringBuilder();
        int actualLength = Math.min(length, bytes.length);
        
        for (int i = 0; i < actualLength; i++) {
            sb.append(String.format("%02X ", bytes[i] & 0xFF));
        }
        
        return sb.toString().trim();
    }
}