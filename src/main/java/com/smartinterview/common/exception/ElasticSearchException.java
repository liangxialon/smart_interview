package com.smartinterview.common.exception;


import com.smartinterview.handler.GlobalHandlerException;

public class ElasticSearchException extends BaseException {
    public ElasticSearchException(){

    }
    public ElasticSearchException(String msg){
        super(msg);
    }
}
