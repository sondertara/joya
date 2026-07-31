package com.sondertara.joya.core.query;

public enum LinkType {
    /**
     *
     */
    AND,
    OR;

    @Override
    public String toString() {
        return " " + name() + " ";
    }
}