package com.auditlog.service;

public class MissingRequestValueException extends IllegalArgumentException {

  public MissingRequestValueException(String message) {
    super(message);
  }
}
