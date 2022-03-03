package com.books.dubbo.demo.api;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;

@Getter
@Setter
@ToString
public class Result<T> implements Serializable {
    private static final long serialVersionUID = 1L;
    private T data;
    private boolean sucess;
    private String msg;
}
