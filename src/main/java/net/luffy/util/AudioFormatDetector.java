package net.luffy.util;

/**
 * 音频格式检测器
 * 用于检测音频文件格式并判断QQ兼容性
 */
public class AudioFormatDetector {
    
    /**
     * 检测音频格式
     * @param header 音频文件头字节数组（至少16字节）
     * @return 音频格式字符串
     */
    public static String detectFormat(byte[] header) {
        if (header == null || header.length < 4) {
            return "UNKNOWN";
        }
        
        // AAC格式检测（ADTS格式）- 优先检测，避免与MP3混淆
        if (isAACFormat(header)) {
            return "AAC";
        }
        
        // MP3格式检测
        if (isMP3Format(header)) {
            return "MP3";
        }
        
        // WAV格式检测
        if (isWAVFormat(header)) {
            return "WAV";
        }
        
        // AMR格式检测
        if (isAMRFormat(header)) {
            return "AMR";
        }
        
        // SILK格式检测
        if (isSILKFormat(header)) {
            return "SILK";
        }
        
        // OGG格式检测
        if (isOGGFormat(header)) {
            return "OGG";
        }
        
        // M4A/AAC格式检测
        if (isM4AFormat(header)) {
            return "M4A";
        }
        
        return "UNKNOWN";
    }
    
    /**
     * 判断音频格式是否与QQ兼容
     * @param format 音频格式
     * @return true表示兼容，false表示不兼容
     */
    public static boolean isQQCompatible(String format) {
        if (format == null) {
            return false;
        }
        
        switch (format.toUpperCase()) {
            case "AMR":
            case "SILK":
                return true;
            default:
                return false;
        }
    }
    
    /**
     * 获取格式兼容性描述
     * @param format 音频格式
     * @return 兼容性描述
     */
    public static String getCompatibilityDescription(String format) {
        if (format == null) {
            return "未知格式 - 兼容性未知";
        }
        
        switch (format.toUpperCase()) {
            case "AMR":
                return "AMR格式 - QQ原生支持";
            case "SILK":
                return "SILK格式 - QQ原生支持（腾讯专用）";
            case "MP3":
                return "MP3格式 - 需要转换为AMR/SILK";
            case "WAV":
                return "WAV格式 - 需要转换为AMR/SILK";
            case "AAC":
                return "AAC格式 - 需要转换为AMR/SILK";
            case "M4A":
                return "M4A/AAC格式 - 需要转换为AMR/SILK";
            case "OGG":
                return "OGG格式 - 需要转换为AMR/SILK";
            default:
                return format + "格式 - 可能需要转换";
        }
    }
    
    /**
     * 检测是否为MP3格式
     */
    private static boolean isMP3Format(byte[] header) {
        if (header.length < 3) {
            return false;
        }
        
        // ID3 tag检测（优先检测，更准确）
        if (header[0] == 'I' && header[1] == 'D' && header[2] == '3') {
            return true;
        }
        
        // MP3 frame header检测（更严格的检测）
        if (header.length >= 4 && 
            (header[0] & 0xFF) == 0xFF && 
            (header[1] & 0xE0) == 0xE0) {
            // 进一步验证是否为有效的MP3帧
            int version = (header[1] >> 3) & 0x03;
            int layer = (header[1] >> 1) & 0x03;
            // 检查版本和层是否有效
            if (version != 1 && layer != 0) { // 版本不能是保留值，层不能是保留值
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * 检测是否为WAV格式
     */
    private static boolean isWAVFormat(byte[] header) {
        if (header.length < 12) {
            return false;
        }
        
        return header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F' &&
               header[8] == 'W' && header[9] == 'A' && header[10] == 'V' && header[11] == 'E';
    }
    
    /**
     * 检测是否为AMR格式
     */
    private static boolean isAMRFormat(byte[] header) {
        if (header.length < 6) {
            return false;
        }
        
        // AMR-NB格式
        if (header[0] == '#' && header[1] == '!' && header[2] == 'A' && 
            header[3] == 'M' && header[4] == 'R' && header[5] == '\n') {
            return true;
        }
        
        // AMR-WB格式
        if (header.length >= 9 &&
            header[0] == '#' && header[1] == '!' && header[2] == 'A' && header[3] == 'M' && 
            header[4] == 'R' && header[5] == '-' && header[6] == 'W' && header[7] == 'B' && header[8] == '\n') {
            return true;
        }
        
        return false;
    }
    
    /**
     * 检测是否为SILK格式
     */
    private static boolean isSILKFormat(byte[] header) {
        if (header.length < 4) {
            return false;
        }
        
        return header[0] == '#' && header[1] == '!' && header[2] == 'S' && header[3] == 'I';
    }
    
    /**
     * 检测是否为OGG格式
     */
    private static boolean isOGGFormat(byte[] header) {
        if (header.length < 4) {
            return false;
        }
        
        return header[0] == 'O' && header[1] == 'g' && header[2] == 'g' && header[3] == 'S';
    }
    
    /**
     * 检测是否为AAC格式（ADTS格式）
     */
    private static boolean isAACFormat(byte[] header) {
        if (header.length < 2) {
            return false;
        }
        
        // AAC ADTS格式检测：FF F0-FF F9
        return (header[0] & 0xFF) == 0xFF && 
               ((header[1] & 0xF0) == 0xF0);
    }
    
    /**
     * 检测是否为M4A格式
     */
    private static boolean isM4AFormat(byte[] header) {
        if (header.length < 8) {
            return false;
        }
        
        // 检查ftyp box
        return header[4] == 'f' && header[5] == 't' && header[6] == 'y' && header[7] == 'p';
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