package net.luffy.util;

public class CronExpressionParser {
    
    public static long parseToMilliseconds(String cronExpression) {
        if (cronExpression == null || cronExpression.trim().isEmpty()) {
            return 5 * 60 * 1000;
        }
        
        String[] parts = cronExpression.trim().split("\\s+");
        if (parts.length != 6) {
            if (parts.length == 5) {
                return parseMinuteBasedCron(parts);
            }
            return 5 * 60 * 1000;
        }
        
        String seconds = parts[0];
        String minutes = parts[1];
        String hours = parts[2];
        
        if (seconds.startsWith("*/")) {
            try {
                int interval = Integer.parseInt(seconds.substring(2));
                return interval * 1000L;
            } catch (NumberFormatException e) {
                // ignore
            }
        }
        
        if (minutes.startsWith("*/")) {
            try {
                int interval = Integer.parseInt(minutes.substring(2));
                return interval * 60 * 1000L;
            } catch (NumberFormatException e) {
                // ignore
            }
        }
        
        if (hours.startsWith("*/")) {
            try {
                int interval = Integer.parseInt(hours.substring(2));
                return interval * 60 * 60 * 1000L;
            } catch (NumberFormatException e) {
                // ignore
            }
        }
        
        if ("*".equals(seconds) && "*".equals(minutes) && !"*".equals(hours)) {
            return 24 * 60 * 60 * 1000L;
        }
        
        return 5 * 60 * 1000L;
    }
    
    private static long parseMinuteBasedCron(String[] parts) {
        String minutes = parts[0];
        String hours = parts[1];
        
        if (minutes.startsWith("*/")) {
            try {
                int interval = Integer.parseInt(minutes.substring(2));
                return interval * 60 * 1000L;
            } catch (NumberFormatException e) {
                // ignore
            }
        }
        
        if (hours.startsWith("*/")) {
            try {
                int interval = Integer.parseInt(hours.substring(2));
                return interval * 60 * 60 * 1000L;
            } catch (NumberFormatException e) {
                // ignore
            }
        }
        
        return 5 * 60 * 1000L;
    }
    
    public static boolean isValidCronExpression(String cronExpression) {
        if (cronExpression == null || cronExpression.trim().isEmpty()) {
            return false;
        }
        String[] parts = cronExpression.trim().split("\\s+");
        return parts.length == 5 || parts.length == 6;
    }
    
    public static String getCronDescription(String cronExpression) {
        if (!isValidCronExpression(cronExpression)) {
            return "Invalid cron expression";
        }
        
        long intervalMs = parseToMilliseconds(cronExpression);
        
        if (intervalMs < 60 * 1000) {
            return String.format("Execute every %d seconds", intervalMs / 1000);
        } else if (intervalMs < 60 * 60 * 1000) {
            return String.format("Execute every %d minutes", intervalMs / (60 * 1000));
        } else if (intervalMs < 24 * 60 * 60 * 1000) {
            return String.format("Execute every %d hours", intervalMs / (60 * 60 * 1000));
        } else {
            return String.format("Execute every %d days", intervalMs / (24 * 60 * 60 * 1000));
        }
    }
}