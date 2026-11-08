
include(FetchContent)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(BUILD_APPS OFF CACHE BOOL "" FORCE)
set(DISABLE_EXTRA_LIBS ON CACHE BOOL "" FORCE)
set(DISABLE_BSON_OUTPUT ON CACHE BOOL "" FORCE)

FetchContent_Declare(json-c
	URL https://s3.amazonaws.com/json-c_releases/releases/json-c-0.17.tar.gz
	URL_HASH SHA256=7550914d58fb63b2c3546f3ccfbe11f1c094147bd31a69dcd23714d7956159e6
)
FetchContent_MakeAvailable(json-c)

# Chiaki includes headers as <json-c/json_object.h> but FetchContent provides
# them without the json-c/ prefix. Create a symlink bridge directory.
set(JSONC_PREFIX_INCLUDE_DIR "${CMAKE_BINARY_DIR}/_deps/json-c-prefix-include")
file(MAKE_DIRECTORY "${JSONC_PREFIX_INCLUDE_DIR}")
if(NOT EXISTS "${JSONC_PREFIX_INCLUDE_DIR}/json-c")
	file(CREATE_LINK "${json-c_SOURCE_DIR}" "${JSONC_PREFIX_INCLUDE_DIR}/json-c" SYMBOLIC)
endif()
