package ru.shtabklassa.service;

// слушается после коммита, иначе сводка посчитает старое
public record AnswersChanged(SummaryTarget target) {
}
