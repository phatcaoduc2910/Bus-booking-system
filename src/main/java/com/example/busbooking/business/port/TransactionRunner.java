package com.example.busbooking.business.port;

import java.util.function.Supplier;


public interface TransactionRunner {
    <T> T inTransaction(Supplier<T> work);
    <T> T inReadOnlyTransaction(Supplier<T> work);
    <T> T inNewTransaction(Supplier<T> work);
}
