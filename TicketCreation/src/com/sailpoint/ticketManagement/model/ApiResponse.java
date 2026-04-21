package com.sailpoint.ticketManagement.model;

public class ApiResponse {

    private boolean success;
    private String message;
    private Object data;

    public static ApiResponse success(String message) {
        ApiResponse r = new ApiResponse();
        r.setSuccess(true);
        r.setMessage(message);
        return r;
    }

    public static ApiResponse success(String message, Object data) {
        ApiResponse r = new ApiResponse();
        r.setSuccess(true);
        r.setMessage(message);
        r.setData(data);
        return r;
    }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public Object getData() { return data; }
    public void setData(Object data) { this.data = data; }
}
