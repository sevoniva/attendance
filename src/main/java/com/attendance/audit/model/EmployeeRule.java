package com.attendance.audit.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity
public class EmployeeRule {

    @Id
    private String employeeId;
    private String name;
    private String lunchType;
    private boolean dormitoryLunch;
    private boolean flexibleLunch;
    private boolean dinnerDeduct;

    public EmployeeRule() {
    }

    public EmployeeRule(String employeeId, String name, boolean dormitoryLunch, boolean flexibleLunch, boolean dinnerDeduct) {
        this.employeeId = employeeId;
        this.name = name;
        this.dormitoryLunch = dormitoryLunch;
        this.flexibleLunch = flexibleLunch;
        this.dinnerDeduct = dinnerDeduct;
    }

    public String getEmployeeId() {
        return employeeId;
    }

    public void setEmployeeId(String employeeId) {
        this.employeeId = employeeId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getLunchType() {
        return lunchType;
    }

    public void setLunchType(String lunchType) {
        this.lunchType = lunchType;
    }

    public boolean isDormitoryLunch() {
        return dormitoryLunch;
    }

    public void setDormitoryLunch(boolean dormitoryLunch) {
        this.dormitoryLunch = dormitoryLunch;
    }

    public boolean isFlexibleLunch() {
        return flexibleLunch;
    }

    public void setFlexibleLunch(boolean flexibleLunch) {
        this.flexibleLunch = flexibleLunch;
    }

    public boolean isDinnerDeduct() {
        return dinnerDeduct;
    }

    public void setDinnerDeduct(boolean dinnerDeduct) {
        this.dinnerDeduct = dinnerDeduct;
    }
}
