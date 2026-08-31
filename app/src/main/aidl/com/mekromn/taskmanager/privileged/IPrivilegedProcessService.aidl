package com.mekromn.taskmanager.privileged;

interface IPrivilegedProcessService {
    String exec(String command);
    void destroy();
}
