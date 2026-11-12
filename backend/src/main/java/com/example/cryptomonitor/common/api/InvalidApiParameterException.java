package com.example.cryptomonitor.common.api;

/** 入力検証で判定した不正だけを400へ変換する。内部のIllegalArgumentExceptionとは区別する。 */
public class InvalidApiParameterException extends RuntimeException {
    /** 検証処理が用意した公開可能な固定文言を受け取る。利用者の入力全文は含めない。 */
    public InvalidApiParameterException(String message) { super(message); }
}
