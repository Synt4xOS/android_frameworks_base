/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.flags;

import android.annotation.NonNull;

/**
 * A flag representing a false value.
 *
 * The flag can never be changed or overridden. It is false at compile time.
 *
 * @hide
 */
public final class FusedOffFlag implements Flag<Boolean> {
    private final String mNamespace;
    private final String mName;

    FusedOffFlag(String namespace, String name) {
        mNamespace = namespace;
        mName = name;
    }

    @Override
    @NonNull
    public Boolean getDefault() {
        return false;
    }

    @Override
    @NonNull
    public String getNamespace() {
        return mNamespace;
    }

    @Override
    @NonNull
    public String getName() {
        return mName;
    }

    @Override
    @NonNull
    public String toString() {
        return getNamespace() + "." + getName() + "[false]";
    }
}
