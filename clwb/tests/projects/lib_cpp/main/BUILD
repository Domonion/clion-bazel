load("@rules_cc//cc:defs.bzl", "cc_binary")

exports_files(
    [
        "BUILD",
        "main.cc",
        "shadow/string.h",
    ],
    visibility = ["//clwb:__pkg__"],
)

cc_binary(
    name = "main_cc",
    copts = [
        "-iquote",
        "main/shadow",
    ],
    srcs = ["main.cc"],
)
