package com.arslan.textgrab;

interface IScreenService {
    void destroy() = 16777114;

    ParcelFileDescriptor capture(boolean png) = 1;

    int exec(String command) = 2;
}
